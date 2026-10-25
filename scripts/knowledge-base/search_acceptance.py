#!/usr/bin/env python3
"""Stage 5 HTTP acceptance using configured model service and real uploaded source files. Retains fixture data."""
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

    def call(method, path, body=None, expected=200, headers=None, authenticated=True, raw=False):
        request_headers = {"X-Request-Id": "search-check-" + uuid.uuid4().hex}
        if authenticated:
            request_headers["Authorization"] = "Basic " + token
        if body is not None and not isinstance(body, bytes):
            request_headers["Content-Type"] = "application/json"
            body = json.dumps(body).encode()
        request_headers.update(headers or {})
        request = urllib.request.Request(base + path, data=body, headers=request_headers, method=method)
        try:
            response = opener.open(request, timeout=90)
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
            return result

    def wait(job):
        until = time.monotonic() + timeout
        while time.monotonic() < until:
            state = call("GET", "/api/ingestion-jobs/" + job["id"])["data"]
            if state["status"] == "SUCCEEDED":
                return
            if state["status"] in ("FAILED", "CANCELLED"):
                raise AssertionError(f"Cannot search failed fixture ingestion: {state}")
            time.sleep(0.5)
        raise TimeoutError(f"Job {job['id']} not complete; inspect worker, stages and model configuration")

    kb = call("POST", "/api/kbs", {"name": "search-acceptance-" + uuid.uuid4().hex}, expected=201)["data"]
    print("Retained fixture KB:", kb["id"], flush=True)
    root = Path(__file__).resolve().parents[2]
    source_directory = root / "services/backend/src/test/resources/knowledge-base/search"
    docs = {}
    for filename in ("database.md", "backup.md", "cooking.md"):
        source = (source_directory / filename).read_bytes()
        boundary = "kb-" + uuid.uuid4().hex
        body = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{filename}"\r\nContent-Type: text/plain\r\n\r\n'.encode()
                + source + f"\r\n--{boundary}--\r\n".encode())
        doc = call("POST", "/api/kbs/" + kb["id"] + "/documents", body, expected=201,
                   headers={"Content-Type": "multipart/form-data; boundary=" + boundary, "Idempotency-Key": "upload-" + uuid.uuid4().hex})["data"]
        job = call("POST", "/api/documents/" + doc["id"] + "/ingestion-jobs", {"requestId": "ingest-" + uuid.uuid4().hex}, expected=202)["data"]
        wait(job)
        docs[filename] = doc
    request = {"query": "PostgreSQL connection", "kbIds": [kb["id"]], "topK": 3, "strategy": "VECTOR", "rerank": False}
    response = call("POST", "/api/knowledge/search", request)
    hits = response["data"]
    assert len(hits) == 3 and len({hit["chunkId"] for hit in hits}) == 3
    assert [hit["score"] for hit in hits] == sorted((hit["score"] for hit in hits), reverse=True)
    for hit in hits:
        assert hit["kbId"] == kb["id"] and hit["documentId"] in {doc["id"] for doc in docs.values()}
        assert hit["scoreType"] == "COSINE_SIMILARITY" and math.isfinite(hit["score"]) and -1 <= hit["score"] <= 1
        assert hit["generation"] == 1 and hit["chunkIndex"] == 0 and hit["content"]
        assert hit["fileUrl"] == "/api/documents/" + hit["documentId"] + "/file"
        assert "pageNumber" not in hit["sourceMetadata"] and "objectKey" not in hit
    selected = dict(request, documentIds=[docs["backup.md"]["id"]])
    assert [hit["documentId"] for hit in call("POST", "/api/knowledge/search", selected)["data"]] == [docs["backup.md"]["id"]]
    bounded = call("POST", "/api/knowledge/search", dict(request, minScore=0.9))["data"]
    assert all(hit["score"] >= 0.9 for hit in bounded)
    empty = call("POST", "/api/kbs", {"name": "empty-search-" + uuid.uuid4().hex}, expected=201)["data"]
    assert call("POST", "/api/knowledge/search", dict(request, kbIds=[empty["id"]]))["data"] == []
    call("POST", "/api/knowledge/search", dict(selected, kbIds=[empty["id"]]), expected=400)
    call("POST", "/api/knowledge/search", dict(request, topK=201), expected=400)
    call("POST", "/api/knowledge/search", dict(request, tenantId="ignored-owner"), expected=400)
    call("POST", "/api/knowledge/search", request, expected=401, authenticated=False)
    assert call("GET", "/api/documents/" + docs["database.md"]["id"] + "/file", raw=True) == (source_directory / "database.md").read_bytes()
    filtered_request = dict(request, minScore=0.7)
    filtered_response = call("POST", "/api/knowledge/search", filtered_request)
    assert all(hit["score"] >= 0.7 for hit in filtered_response["data"])
    example = {"method": "POST", "path": "/api/knowledge/search", "request": filtered_request, "response": filtered_response,
               "sourceFiles": [str((source_directory / name).relative_to(root)).replace("\\", "/") for name in docs],
               "vectorSource": os.environ.get("KB_ACCEPTANCE_VECTOR_SOURCE", "Configured AI service; see deployment configuration")}
    if os.environ.get("KB_SEARCH_EXAMPLE_OUTPUT"):
        output = Path(os.environ["KB_SEARCH_EXAMPLE_OUTPUT"])
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(example, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(example, ensure_ascii=False))


if __name__ == "__main__":
    main()
