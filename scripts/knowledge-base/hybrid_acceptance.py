#!/usr/bin/env python3
"""Real HTTP retrieval acceptance, stdlib only. Retains only its own uniquely named fixtures.

Keyword/Rerank checks follow server capabilities. KB_REQUIRE_HYBRID=1 makes missing ES a failure.
This checks protocol and lifecycle behavior, not the semantic quality of a live model.
"""
import base64
import json
import math
import os
from pathlib import Path
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid


def main():
    base = os.environ.get("KB_BASE_URL", "http://127.0.0.1:8080").rstrip("/")
    origin = urllib.parse.urlsplit(base)
    if origin.scheme not in ("http", "https") or not origin.hostname or origin.username or origin.password:
        raise ValueError("KB_BASE_URL must be HTTP(S) without credentials")
    if origin.scheme == "http" and origin.hostname not in ("localhost", "127.0.0.1", "::1"):
        raise ValueError("Use HTTPS outside localhost")
    timeout = int(os.environ.get("KB_ACCEPTANCE_WAIT_SECONDS", "300"))
    if not 1 <= timeout <= 3600:
        raise ValueError("KB_ACCEPTANCE_WAIT_SECONDS must be 1..3600")
    token = base64.b64encode((os.environ["KB_USERNAME"] + ":" + os.environ["KB_PASSWORD"]).encode()).decode()

    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            raise ValueError("Acceptance requests must not redirect credentials")

    opener = urllib.request.build_opener(NoRedirect())
    calls = []

    def call(method, path, body=None, expected=200, headers=None, authenticated=True, raw=False):
        request_headers = {"X-Request-Id": "hybrid-check-" + uuid.uuid4().hex}
        if authenticated:
            request_headers["Authorization"] = "Basic " + token
        original = body
        if body is not None and not isinstance(body, bytes):
            request_headers["Content-Type"] = "application/json"
            body = json.dumps(body).encode()
        request_headers.update(headers or {})
        request = urllib.request.Request(base + path, data=body, headers=request_headers, method=method)
        try:
            response = opener.open(request, timeout=130)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            payload = response.read(2 * 1024 * 1024)
            if response.status != expected:
                raise AssertionError(f"{method} {path}: expected {expected}, got {response.status}: {payload[:256]!r}")
            if raw:
                return payload
            result = json.loads(payload)
            if expected < 300:
                assert result["code"] == "0"
            calls.append({"method": method, "path": path, "request": "multipart fixture" if isinstance(original, bytes) else original,
                          "status": response.status, "response": result})
            return result["data"] if expected < 300 else result

    def wait(path, predicate):
        until = time.monotonic() + timeout
        while time.monotonic() < until:
            state = call("GET", path)
            if predicate(state):
                return state
            if state.get("status") in ("FAILED", "CANCELLED"):
                raise AssertionError(f"Fixture failed: {state}")
            time.sleep(0.5)
        raise TimeoutError("Fixture incomplete; inspect worker, stages and keyword-index state")

    capabilities = call("GET", "/api/knowledge/search-capabilities")
    keyword = capabilities["keywordConfigured"]
    if os.environ.get("KB_REQUIRE_HYBRID") == "1" and not keyword:
        raise AssertionError("KB_REQUIRE_HYBRID requires a configured Elasticsearch channel")
    kb = call("POST", "/api/kbs", {"name": "hybrid-acceptance-" + uuid.uuid4().hex}, 201)
    print("Retained fixture KB:", kb["id"], flush=True)
    sources = {
        "manual.md": "# 项目运维\n\n专有产品麒麟数据库，错误编号 ZX-1472，执行 PostgreSQL 备份。\n",
        "recovery.md": "# Recovery\n\nDatabase backup uses pg_dump and durable snapshots.\n",
        "cooking.md": "# Kitchen\n\nBaking bread with flour.\n",
    }
    docs = []
    for filename, source in sources.items():
        boundary = "kb-" + uuid.uuid4().hex
        body = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{filename}"\r\nContent-Type: text/plain\r\n\r\n'.encode()
                + source.encode() + f"\r\n--{boundary}--\r\n".encode())
        doc = call("POST", "/api/kbs/" + kb["id"] + "/documents", body, 201,
                   {"Content-Type": "multipart/form-data; boundary=" + boundary, "Idempotency-Key": "upload-" + uuid.uuid4().hex})
        path = "/api/documents/" + doc["id"]
        job = call("POST", path + "/ingestion-jobs", {"requestId": "ingest-" + uuid.uuid4().hex}, 202)
        wait("/api/ingestion-jobs/" + job["id"], lambda s: s["status"] == "SUCCEEDED")
        if keyword:
            wait(path + "/keyword-index", lambda s: s["status"] == "SUCCEEDED" and s["revision"] == s["syncedRevision"])
        docs.append(doc)

    request = {"query": "database backup", "kbIds": [kb["id"]], "strategy": "HYBRID" if keyword else "VECTOR", "rerank": False}
    result = call("POST", "/api/knowledge/retrieve", request)
    hits = result["evidence"]
    assert hits and len(hits) == len({h["chunkId"] for h in hits})
    assert all(h["generation"] == 1 and h["content"] and h["fileUrl"].startswith("/api/documents/") for h in hits)
    assert all(math.isfinite(h["score"]) for h in hits)
    if keyword:
        assert result["strategy"] == "KEYWORD+VECTOR" and all(c["status"] == "SUCCEEDED" for c in result["channels"]), result
        assert any(len(h["channelScores"]) == 2 for h in hits)
        assert all(h["scoreType"] == "RRF" and h["fusionScore"] == h["score"] for h in hits)
        numbered = call("POST", "/api/knowledge/retrieve", dict(request, query="ZX-1472", strategy="KEYWORD"))
        assert len(numbered["evidence"]) == 1 and numbered["evidence"][0]["documentId"] == docs[0]["id"]
        assert numbered["evidence"][0]["channelScores"][0]["scoreType"] == "BM25"
        assert call("POST", "/api/knowledge/retrieve", dict(request, query="ABSENT_987654321", strategy="KEYWORD"))["evidence"] == []
        assert call("GET", "/api/documents/" + docs[0]["id"] + "/file", raw=True) == sources["manual.md"].encode()
    else:
        print("SKIP: keyword channel not configured; real vector retrieval checked", flush=True)
    if capabilities["rerankConfigured"]:
        ranked = call("POST", "/api/knowledge/retrieve", dict(request, rerank=True))
        assert ranked["rerankStatus"] == "APPLIED", ranked
        assert ranked["evidence"] and all(h["rerankScore"] is not None for h in ranked["evidence"])
        assert [h["rerankScore"] for h in ranked["evidence"]] == sorted((h["rerankScore"] for h in ranked["evidence"]), reverse=True)
    else:
        print("SKIP: rerank not configured", flush=True)
    path = "/api/documents/" + docs[0]["id"]
    doc = call("GET", path)
    call("PATCH", path + "/enabled", {"version": doc["version"], "enabled": False})
    assert all(h["documentId"] != docs[0]["id"] for h in call("POST", "/api/knowledge/retrieve", request)["evidence"])
    call("POST", "/api/knowledge/retrieve", request, expected=401, authenticated=False)
    call("POST", "/api/knowledge/retrieve", dict(request, topK=201), expected=400)
    output = {"capabilities": capabilities, "kbId": kb["id"], "calls": calls,
              "environment": os.environ.get("KB_ACCEPTANCE_VECTOR_SOURCE", "Configured model service; semantic quality is not assessed by this script")}
    if os.environ.get("KB_HYBRID_EXAMPLE_OUTPUT"):
        target = Path(os.environ["KB_HYBRID_EXAMPLE_OUTPUT"])
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("PASS: actual configured retrieval channels, duplicate fusion, separate scores, sources, disable filtering and input boundaries")


if __name__ == "__main__":
    main()
