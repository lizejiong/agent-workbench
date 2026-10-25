#!/usr/bin/env python3
"""Stage 7 real HTTP acceptance. Retains its own fixture KB for inspection; external calls are opt-in."""
import base64
import hashlib
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
    token = base64.b64encode((os.environ["KB_USERNAME"] + ":" + os.environ["KB_PASSWORD"]).encode()).decode()
    budget = int(os.environ.get("KB_ACCEPTANCE_WAIT_SECONDS", "300"))
    if not 1 <= budget <= 3600:
        raise ValueError("KB_ACCEPTANCE_WAIT_SECONDS must be 1..3600")
    external = os.environ.get("KB_ACCEPTANCE_EXTERNAL_FORMATS", "0") == "1"

    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            raise ValueError("Acceptance requests must not redirect credentials")

    opener = urllib.request.build_opener(NoRedirect())
    calls = []

    def call(method, path, body=None, status=200, headers=None):
        values = {"Authorization": "Basic " + token, "X-Request-Id": "format-check-" + uuid.uuid4().hex}
        request_body = body
        if body is not None and not isinstance(body, bytes):
            values["Content-Type"] = "application/json"
            body = json.dumps(body).encode()
        values.update(headers or {})
        try:
            response = opener.open(urllib.request.Request(base + path, data=body, headers=values, method=method), timeout=130)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            data = response.read(2 * 1024 * 1024)
            if response.status != status:
                raise AssertionError(f"{method} {path}: expected {status}, got {response.status}: {data[:256]!r}")
            result = json.loads(data)
            if status < 300:
                assert result["code"] == "0"
            calls.append({"method": method, "path": path, "request": "multipart fixture" if isinstance(request_body, bytes) else request_body,
                          "status": response.status, "response": result})
            return result["data"] if status < 300 else result

    matrix = call("GET", "/api/knowledge/formats")
    kb = call("POST", "/api/kbs", {"name": "formats-acceptance-" + uuid.uuid4().hex}, 201)
    print("Retained fixture KB:", kb["id"], flush=True)
    root = Path(__file__).resolve().parents[2]
    directory = root / "services/backend/src/test/resources/knowledge-base/formats"
    results = {}

    def upload(name, profile="fast"):
        source = (directory / name).read_bytes()
        boundary = "kb-" + uuid.uuid4().hex
        metadata = {"ingestionSpec": {"parseProfile": profile, "splitter": {"tableRows": 1}}}
        body = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{name}"\r\nContent-Type: application/octet-stream\r\n\r\n'.encode()
                + source + f'\r\n--{boundary}\r\nContent-Disposition: form-data; name="metadata"\r\nContent-Type: application/json\r\n\r\n'.encode()
                + json.dumps(metadata).encode() + f"\r\n--{boundary}--\r\n".encode())
        doc = call("POST", "/api/kbs/" + kb["id"] + "/documents", body, 201,
                   {"Content-Type": "multipart/form-data; boundary=" + boundary, "Idempotency-Key": "upload-" + uuid.uuid4().hex})
        return doc, hashlib.sha256(source).hexdigest()

    def ingest(doc, expected="SUCCEEDED"):
        job = call("POST", "/api/documents/" + doc["id"] + "/ingestion-jobs", {"requestId": "ingest-" + uuid.uuid4().hex}, 202)
        until = time.monotonic() + budget
        while time.monotonic() < until:
            state = call("GET", "/api/ingestion-jobs/" + job["id"])
            if state["status"] in ("SUCCEEDED", "FAILED", "CANCELLED"):
                assert state["status"] == expected, state
                return state
            time.sleep(0.5)
        raise TimeoutError(f"Job not terminal: {job['id']}; inspect PARSE stage and configured limits")

    for name in ("ordinary.pdf", "handbook.docx", "slides.pptx", "legacy.ppt", "multi-sheet.xls", "multi-sheet.xlsx", "quoted.csv"):
        doc, digest = upload(name)
        job = ingest(doc)
        chunks = call("GET", "/api/documents/" + doc["id"] + "/chunks?size=100")["records"]
        assert chunks
        if name.startswith("multi-sheet"):
            assert len(chunks) == 4
            assert chunks[0]["metadata"]["rowNumbers"] == [2] and chunks[2]["metadata"]["rowNumbers"] == [5]
            assert "总额：3.00" in chunks[0]["embeddingText"]
        if name == "handbook.docx":
            assert chunks[0]["metadata"]["chapterPath"] == ["Database handbook"]
        results[name] = {"documentId": doc["id"], "sha256": digest, "job": job, "chunks": chunks, "verification": "local parser, server-configured embedding"}
        print("PASS local:", name, "chunks=", len(chunks), flush=True)

    scanned, digest = upload("scanned.pdf")
    failed = ingest(scanned, "FAILED")
    assert failed["errorCode"] == "KB_OCR_REQUIRED"
    results["scanned.pdf-fast"] = {"documentId": scanned["id"], "sha256": digest, "job": failed, "verification": "local explicit no-OCR rejection"}
    mineru_ready = any("pdf" in row["extensions"] and row["fidelity"] == "MINERU_CONFIGURED" for row in matrix)
    vlm_ready = any("png" in row["extensions"] and row["fast"] == "VLM_CONFIGURED" for row in matrix)
    if external and mineru_ready:
        latest = call("GET", "/api/documents/" + scanned["id"])
        call("PATCH", "/api/documents/" + scanned["id"], {"version": latest["version"], "ingestionSpec": {"parseProfile": "fidelity"}})
        job = ingest(scanned)
        chunks = call("GET", "/api/documents/" + scanned["id"] + "/chunks?size=100")["records"]
        results["scanned.pdf-fidelity"] = {"job": job, "chunks": chunks, "verification": "external service as configured; does not establish recognition quality"}
    else:
        print("SKIP external MinerU: set KB_ACCEPTANCE_EXTERNAL_FORMATS=1 and enable compatible service", flush=True)
    if external and vlm_ready:
        for name in ("invoice.png", "invoice.jpeg"):
            doc, digest = upload(name)
            job = ingest(doc)
            chunks = call("GET", "/api/documents/" + doc["id"] + "/chunks?size=100")["records"]
            assert chunks and chunks[0]["metadata"]["sourceBlocks"][0]["generated"]
            results[name] = {"sha256": digest, "job": job, "chunks": chunks, "verification": "external vision as configured; manually review OCR text"}
    else:
        print("SKIP external VLM: set KB_ACCEPTANCE_EXTERNAL_FORMATS=1 and configure AI vision", flush=True)
    output = os.environ.get("KB_FORMAT_EXAMPLE_OUTPUT")
    if output:
        target = Path(output)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps({"note": "Actual HTTP calls. Configured external services may be protocol fixtures; inspect verification environment. Retained fixture UUIDs need the same server.",
                                      "kbId": kb["id"], "matrix": matrix, "formats": results, "calls": calls}, ensure_ascii=False, indent=2), encoding="utf-8")
    print("PASS: binary formats through upload -> durable kernel -> chunks/index; external coverage explicitly reported")


if __name__ == "__main__":
    main()
