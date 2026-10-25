#!/usr/bin/env python3
"""Stage 4 real HTTP acceptance. Requires a configured AI service; retains its fixture KB."""
import base64
import json
import os
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

    def call(method, path, body=None, expected=200, headers=None, authenticated=True):
        request_headers = {"X-Request-Id": "ingestion-check-" + uuid.uuid4().hex}
        if authenticated:
            request_headers["Authorization"] = "Basic " + token
        if body is not None and not isinstance(body, bytes):
            request_headers["Content-Type"] = "application/json"
            body = json.dumps(body).encode()
        request_headers.update(headers or {})
        request = urllib.request.Request(base + path, data=body, headers=request_headers, method=method)
        try:
            response = opener.open(request, timeout=20)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            payload = response.read(1024 * 1024)
            if response.status != expected:
                raise AssertionError(f"{method} {path}: expected {expected}, got {response.status}: {payload[:256]!r}")
            value = json.loads(payload)
            if expected < 300:
                assert value["code"] == "0"
            return value["data"]

    def wait(job):
        until = time.monotonic() + timeout
        while time.monotonic() < until:
            state = call("GET", "/api/ingestion-jobs/" + job["id"])
            if state["status"] == "SUCCEEDED":
                return state
            if state["status"] in ("FAILED", "CANCELLED"):
                stages = call("GET", "/api/ingestion-jobs/" + job["id"] + "/stages?size=100")
                raise AssertionError(f"Job ended: {state}; stages: {stages}")
            time.sleep(0.5)
        raise TimeoutError(f"Poll deadline exceeded; inspect job {job['id']} (worker enabled, model/DB/storage)")

    kb = call("POST", "/api/kbs", {"name": "ingestion-acceptance-" + uuid.uuid4().hex}, expected=201)
    print("Retained fixture KB:", kb["id"], flush=True)
    content = b"# Evidence\n\nLocal upload becomes searchable evidence after an explicit durable job.\n\n| Field | Value |\n|---|---|\n| source | local |\n"
    boundary = "kb-" + uuid.uuid4().hex
    body = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="evidence.md"\r\nContent-Type: text/plain\r\n\r\n'.encode()
            + content + f"\r\n--{boundary}--\r\n".encode())
    doc = call("POST", "/api/kbs/" + kb["id"] + "/documents", body, expected=201,
               headers={"Content-Type": "multipart/form-data; boundary=" + boundary, "Idempotency-Key": "upload-" + uuid.uuid4().hex})
    assert doc["processStatus"] == "PENDING" and doc["activeGeneration"] is None
    path = "/api/documents/" + doc["id"]
    request = {"requestId": "ingest-" + uuid.uuid4().hex}
    job = call("POST", path + "/ingestion-jobs", request, expected=202)
    assert call("POST", path + "/ingestion-jobs", request)["id"] == job["id"]
    call("GET", "/api/ingestion-jobs/" + job["id"], expected=401, authenticated=False)
    first = wait(job)
    assert first["targetGeneration"] == 1
    stages = call("GET", "/api/ingestion-jobs/" + job["id"] + "/stages?size=100")
    assert len([s for s in stages["records"] if s["status"] == "SUCCEEDED"]) == 6
    current = call("GET", path)
    assert current["processStatus"] == "READY" and current["chunkCount"] > 0
    changed = call("PATCH", path, {"version": current["version"], "ingestionSpec": {"splitter": {"kind": "WHOLE_DOCUMENT"}}})
    assert changed["activeGeneration"] == 1 and changed["processStatus"] == "PENDING"
    next_job = call("POST", path + "/ingestion-jobs", {"requestId": "reingest-" + uuid.uuid4().hex}, expected=202)
    wait(next_job)
    ready = call("GET", path)
    assert ready["activeGeneration"] == 2 and ready["chunkCount"] == 1
    history = call("GET", path + "/ingestion-jobs?status=SUCCEEDED")
    assert history["total"] == 2
    print(json.dumps({"documentId": doc["id"], "firstJobId": job["id"], "secondJobId": next_job["id"],
                      "activeGeneration": ready["activeGeneration"], "chunkCount": ready["chunkCount"],
                      "verification": "HTTP submission, idempotency, polling, stage logs and re-chunk publication; vector retrieval checked by integration tests"}))


if __name__ == "__main__":
    main()
