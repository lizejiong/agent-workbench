#!/usr/bin/env python3
"""Stage 2 real HTTP acceptance, stdlib only. Leaves an explicitly reported nonempty fixture KB."""
import base64
import hashlib
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid


def main():
    base = os.environ.get("KB_BASE_URL", "http://127.0.0.1:8080").rstrip("/")
    origin = urllib.parse.urlsplit(base)
    if origin.scheme not in ("http", "https") or not origin.hostname or origin.username or origin.password:
        raise ValueError("KB_BASE_URL must be an HTTP(S) URL without credentials")
    if origin.scheme == "http" and origin.hostname not in ("localhost", "127.0.0.1", "::1"):
        raise ValueError("Use HTTPS outside localhost")
    token = base64.b64encode((os.environ["KB_USERNAME"] + ":" + os.environ["KB_PASSWORD"]).encode()).decode()

    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            raise ValueError("Acceptance requests must not redirect credentials")

    opener = urllib.request.build_opener(NoRedirect())

    def call(method, path, body=None, expected=200, raw=False, authenticated=True, headers=None):
        request_headers = {"X-Request-Id": "upload-check-" + uuid.uuid4().hex}
        if authenticated:
            request_headers["Authorization"] = "Basic " + token
        if body is not None and not isinstance(body, bytes):
            request_headers["Content-Type"] = "application/json"
            body = json.dumps(body).encode()
        request_headers.update(headers or {})
        request = urllib.request.Request(base + path, data=body, headers=request_headers, method=method)
        try:
            response = opener.open(request, timeout=120)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            data = response.read()
            if response.status != expected:
                raise AssertionError(f"{method} {path}: expected {expected}, got {response.status}: {data[:256]!r}")
            if raw:
                return data
            value = json.loads(data)
            assert value["requestId"] == response.headers["X-Request-Id"]
            if expected < 300:
                assert value["code"] == "0"
            return value

    def upload(kb, filename, content, key, expected=201, metadata=None):
        boundary = "kb-boundary-" + uuid.uuid4().hex
        parts = [f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{filename}"\r\nContent-Type: application/octet-stream\r\n\r\n'.encode(), content, b"\r\n"]
        if metadata is not None:
            parts += [f'--{boundary}\r\nContent-Disposition: form-data; name="metadata"\r\nContent-Type: application/json\r\n\r\n'.encode(), json.dumps(metadata).encode(), b"\r\n"]
        parts.append(f"--{boundary}--\r\n".encode())
        return call("POST", f"/api/kbs/{kb}/documents", b"".join(parts), expected=expected,
                    headers={"Content-Type": "multipart/form-data; boundary=" + boundary, "Idempotency-Key": key})

    profiles = call("GET", "/api/kb-models")["data"]
    assert profiles
    call("POST", "/api/kbs", {"name": "invalid model", "embeddingProfileId": "not-allowed"}, expected=400)
    name = "upload-acceptance-" + uuid.uuid4().hex
    kb = call("POST", "/api/kbs", {"name": name, "embeddingProfileId": profiles[0]["id"]}, expected=201)["data"]
    print("Fixture KB (retained because document deletion belongs to a later stage):", kb["id"], flush=True)
    assert kb["embeddingDimension"] == 1024 and len(kb["embeddingConfigRevision"]) == 64
    path = "/api/kbs/" + kb["id"]
    call("POST", "/api/kbs", {"name": name.upper()}, expected=409)
    content = "# 本地验收\n\n上传后保持 PENDING，不生成向量。\n".encode()
    key = "acceptance-" + uuid.uuid4().hex
    doc = upload(kb["id"], "guide.md", content, key, metadata={"name": "guide"})["data"]
    assert doc["processStatus"] == "PENDING" and doc["activeGeneration"] is None and doc["chunkCount"] == 0
    assert doc["contentHash"] == hashlib.sha256(content).hexdigest() and doc["fileSize"] == len(content)
    assert doc["mimeType"] in ("text/plain", "text/markdown", "text/x-web-markdown") and doc["originalFilename"] == "guide.md"
    assert upload(kb["id"], "guide.md", content, key, 200, {"name": "guide"})["data"]["id"] == doc["id"]
    upload(kb["id"], "guide.md", b"changed", key, 409, {"name": "guide"})
    doc_path = "/api/documents/" + doc["id"]
    assert call("GET", doc_path)["data"]["objectKey"] == doc["objectKey"]
    assert call("GET", doc_path + "/file", raw=True) == content
    call("GET", doc_path + "/file", expected=401, authenticated=False)
    assert call("GET", path + "/documents?name=guide&status=PENDING&size=1")["data"]["total"] == 1
    updated = call("PATCH", doc_path, {"version": 1, "name": "renamed", "ingestionSpec": {"splitter": {"maxChars": 800}}})["data"]
    assert updated["configRevision"] == 2 and updated["processStatus"] == "PENDING"
    call("DELETE", path + "?version=1", expected=409)
    upload(kb["id"], "empty.txt", b"", "empty-" + uuid.uuid4().hex, 400)
    upload(kb["id"], "bad.pdf", b"fake", "bad-" + uuid.uuid4().hex, 415)
    upload(kb["id"], "invalid.txt", b"text", "spec-" + uuid.uuid4().hex, 400,
           {"ingestionSpec": {"splitter": {"maxChars": 128, "overlapChars": 128}}})
    if "KB_ACCEPTANCE_MAX_BYTES" in os.environ:
        limit = int(os.environ["KB_ACCEPTANCE_MAX_BYTES"])
        if not 1 <= limit <= 64 * 1024 * 1024:
            raise ValueError("Acceptance limit check supports 1..64MiB")
        upload(kb["id"], "large.txt", b"A" * (limit + 1), "size-" + uuid.uuid4().hex, 413)
    assert call("GET", path + "/storage-operations")["data"]["records"][0]["status"] == "COMPLETED"
    print("PASS: models, KB CRUD rules, multipart upload, SHA-256, MIME, pending state, idempotency, download/auth, filters, metadata, invalid input")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"FAIL: {type(error).__name__}: {error}", file=sys.stderr)
        sys.exit(1)
