#!/usr/bin/env python3
"""Stage 6 real HTTP acceptance. Creates and removes only its own fixture KB/documents."""
import base64
import json
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
    evidence = []

    def call(method, path, body=None, expected=200, headers=None, authenticated=True):
        request_headers = {"X-Request-Id": "maintenance-check-" + uuid.uuid4().hex}
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
            result = json.loads(payload)
            if expected < 300:
                assert result["code"] == "0"
            evidence.append({"method": method, "path": path, "request": "multipart fixture" if isinstance(original, bytes) else original,
                             "status": response.status, "response": result})
            return result["data"] if expected < 300 else result

    def wait(path):
        until = time.monotonic() + timeout
        while time.monotonic() < until:
            state = call("GET", path)
            if state["status"] == "SUCCEEDED":
                return state
            if state["status"] in ("FAILED", "CANCELLED"):
                raise AssertionError(f"Fixture work failed; inspect stage/cleanup: {state}")
            time.sleep(0.5)
        raise TimeoutError(f"Pending {path}; inspect enabled worker, lease, model and storage permissions")

    kb = call("POST", "/api/kbs", {"name": "maintenance-acceptance-" + uuid.uuid4().hex}, 201)
    print("Owned fixture KB:", kb["id"], flush=True)
    source = b"Database PostgreSQL connection evidence.\n"
    boundary = "kb-" + uuid.uuid4().hex
    body = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="database.txt"\r\nContent-Type: text/plain\r\n\r\n'.encode()
            + source + f"\r\n--{boundary}--\r\n".encode())
    doc = call("POST", "/api/kbs/" + kb["id"] + "/documents", body, 201,
               {"Content-Type": "multipart/form-data; boundary=" + boundary, "Idempotency-Key": "upload-" + uuid.uuid4().hex})
    path = "/api/documents/" + doc["id"]
    job = call("POST", path + "/ingestion-jobs", {"requestId": "ingest-" + uuid.uuid4().hex}, 202)
    wait("/api/ingestion-jobs/" + job["id"])
    original = call("GET", path + "/chunks")["records"][0]

    def version():
        return call("GET", path)["version"]

    changed = call("POST", path + "/chunks", {"version": version(), "content": "Baking bread, manual addition."}, 201)
    added = changed["chunks"][0]
    edited = "Baking bread, edited by a knowledge curator."
    changed = call("PATCH", path + "/chunks/" + original["id"], {"version": changed["documentVersion"], "content": edited})
    assert changed["chunks"][0]["embeddingText"] == edited
    assert changed["chunks"][0]["edited"] and changed["chunks"][0]["tokenCountEstimated"]

    def search():
        return call("POST", "/api/knowledge/search", {"query": edited, "kbIds": [kb["id"]]})

    hits = search()
    assert any(hit["chunkId"] == original["id"] and hit["content"] == edited for hit in hits)
    call("PATCH", path + "/chunks/" + original["id"] + "/enabled", {"version": version(), "enabled": False})
    assert all(hit["chunkId"] != original["id"] for hit in search())
    call("PATCH", path + "/enabled", {"version": version(), "enabled": False})
    assert search() == []
    call("PATCH", path + "/enabled", {"version": version(), "enabled": True})
    hits = search()
    assert [hit["chunkId"] for hit in hits] == [added["id"]]
    call("POST", path + "/chunks/batch-enabled", {"version": version(), "enabled": False, "chunkIds": []}, 400)
    call("POST", path + "/chunks/batch-enabled", {"version": version(), "enabled": False, "chunkIds": [added["id"]]})
    assert search() == []
    call("POST", path + "/chunks/batch-enabled", {"version": version(), "enabled": True, "applyAll": True})
    assert len(search()) == 2
    request_id = "replace-" + uuid.uuid4().hex
    error = call("POST", path + "/ingestion-jobs", {"requestId": request_id}, 409)
    assert error["code"] == "KB_MANUAL_OVERWRITE_REQUIRED"
    request = {"requestId": request_id, "confirmOverwriteEdited": True, "expectedDocumentVersion": version()}
    job = call("POST", path + "/ingestion-jobs", request, 202)
    assert call("POST", path + "/ingestion-jobs", request)["id"] == job["id"]
    wait("/api/ingestion-jobs/" + job["id"])
    assert not call("GET", path)["hasManualChanges"]
    assert call("GET", path + "/chunks")["total"] == 1
    manual = call("POST", path + "/chunks", {"version": version(), "content": "Baking bread, temporary manual chunk."}, 201)["chunks"][0]
    changed = call("DELETE", path + "/chunks/" + manual["id"] + "?version=" + str(version()))
    assert changed["chunkCount"] == 1
    receipt = call("DELETE", path + "?version=" + str(version()), expected=202)
    assert call("DELETE", path + "?version=1", expected=202)["id"] == receipt["id"]
    call("GET", path, expected=404)
    call("GET", path + "/file", expected=404)
    assert search() == []
    wait("/api/document-cleanups/" + receipt["id"])
    capabilities = call("GET", "/api/knowledge/search-capabilities")
    if capabilities["keywordConfigured"]:
        # PG visibility is immediate; external BM25 removal has its own durable receipt/grace period.
        until = time.monotonic() + timeout
        while time.monotonic() < until:
            states = call("GET", "/api/kbs/" + kb["id"] + "/keyword-sync")["records"]
            if all(s["status"] == "SUCCEEDED" and s["revision"] == s["syncedRevision"] and s["cleanupStatus"] == "SUCCEEDED" for s in states):
                break
            if any(s["status"] == "FAILED" or s["cleanupStatus"] == "FAILED" for s in states):
                raise AssertionError("Keyword cleanup failed; inspect keyword-sync and retry after fixing Elasticsearch")
            time.sleep(1)
        else:
            raise TimeoutError("Keyword cleanup pending; KB deletion remains protected")
    call("GET", "/api/document-cleanups/" + receipt["id"], expected=401, authenticated=False)
    base_version = call("GET", "/api/kbs/" + kb["id"])["version"]
    call("DELETE", "/api/kbs/" + kb["id"] + "?version=" + str(base_version))
    output = os.environ.get("KB_MAINTENANCE_EXAMPLE_OUTPUT")
    if output:
        target = Path(output)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps({"note": "Actual HTTP calls. Fixtures created and deleted by this script; model determined by server configuration.",
                                      "kbId": kb["id"], "documentId": doc["id"], "calls": evidence}, ensure_ascii=False, indent=2), encoding="utf-8")
    print("PASS: manual CRUD, semantic index update, independent toggles, explicit batch scope, overwrite confirmation and durable deletion; fixture removed")


if __name__ == "__main__":
    main()
