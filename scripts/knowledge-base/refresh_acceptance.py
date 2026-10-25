#!/usr/bin/env python3
"""Stage 8 actual HTTP acceptance against a configured backend; retains its own fixture KB.

Default controlled localhost origin requires the explicit server policy documented in RUNBOOK.
No provider mock is installed by this script; backend embedding configuration is used as-is.
"""
import base64
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid


def main():
    base = os.environ.get("KB_BASE_URL", "http://127.0.0.1:8080").rstrip("/")
    parsed = urllib.parse.urlsplit(base)
    if parsed.scheme not in ("https", "http") or not parsed.hostname or parsed.username or parsed.password:
        raise ValueError("KB_BASE_URL must be HTTP(S), without credentials")
    if parsed.scheme == "http" and parsed.hostname not in ("localhost", "127.0.0.1", "::1"):
        raise ValueError("Use HTTPS outside localhost")
    token = base64.b64encode((os.environ["KB_USERNAME"] + ":" + os.environ["KB_PASSWORD"]).encode()).decode()
    wait = int(os.environ.get("KB_ACCEPTANCE_WAIT_SECONDS", "300"))
    if not 1 <= wait <= 3600:
        raise ValueError("KB_ACCEPTANCE_WAIT_SECONDS must be 1..3600")
    source = {"body": "# Database\n\nPostgreSQL connection backup.", "etag": '"v1"', "fail": False, "calls": 0}

    class Origin(BaseHTTPRequestHandler):
        def do_GET(self):
            source["calls"] += 1
            if source["fail"]:
                self.send_response(503)
                self.end_headers()
                return
            if self.headers.get("If-None-Match") == source["etag"]:
                self.send_response(304)
                self.send_header("ETag", source["etag"])
                self.end_headers()
                return
            body = source["body"].encode()
            self.send_response(200)
            self.send_header("Content-Type", "text/markdown")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("ETag", source["etag"])
            self.send_header("Last-Modified", "Wed, 21 Oct 2026 08:00:00 GMT")
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *args):
            pass

    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, *args, **kwargs):
            raise ValueError("Acceptance client will not redirect credentials")

    opener = urllib.request.build_opener(NoRedirect())
    calls = []

    def call(method, path, body=None, status=200, headers=None):
        values = {"Authorization": "Basic " + token, "X-Request-Id": "refresh-check-" + uuid.uuid4().hex}
        payload = None if body is None else json.dumps(body).encode()
        if body is not None:
            values["Content-Type"] = "application/json"
        values.update(headers or {})
        try:
            response = opener.open(urllib.request.Request(base + path, payload, values, method=method), timeout=150)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            raw = response.read(2 * 1024 * 1024)
            if response.status != status:
                raise AssertionError(f"{method} {path}: expected {status}, got {response.status}: {raw[:256]!r}")
            result = json.loads(raw)
            calls.append({"method": method, "path": path, "request": body, "status": response.status, "response": result})
            if status < 300:
                assert result["code"] == "0"
                return result["data"]
            return result

    def poll(path, predicate):
        until = time.monotonic() + wait
        while time.monotonic() < until:
            result = call("GET", path)
            if predicate(result):
                return result
            time.sleep(0.3)
        raise TimeoutError("Persistent work did not finish; inspect refresh-runs and ingestion stages")

    origin_port = int(os.environ.get("KB_REFRESH_SOURCE_PORT", "18081"))
    with ThreadingHTTPServer(("127.0.0.1", origin_port), Origin) as origin:
        thread = threading.Thread(target=origin.serve_forever, daemon=True)
        thread.start()
        source_url = os.environ.get("KB_REFRESH_SOURCE_URL", f"http://127.0.0.1:{origin.server_port}/document.md")
        try:
            kb = call("POST", "/api/kbs", {"name": "refresh-acceptance-" + uuid.uuid4().hex}, 201)
            key = "import-" + uuid.uuid4().hex
            request = {"url": source_url, "filename": "remote.md", "ingest": True}
            imported = call("POST", "/api/kbs/" + kb["id"] + "/documents/import-url", request, 202, {"Idempotency-Key": key})
            doc = imported["document"]; prefix = "/api/documents/" + doc["id"]
            poll("/api/ingestion-jobs/" + imported["jobId"], lambda r: r["status"] in ("SUCCEEDED", "FAILED", "CANCELLED"))
            assert call("GET", "/api/ingestion-jobs/" + imported["jobId"])["status"] == "SUCCEEDED"
            before = call("GET", prefix)
            downloads = source["calls"]
            replay = call("POST", "/api/kbs/" + kb["id"] + "/documents/import-url", request, 200, {"Idempotency-Key": key})
            assert replay["document"]["id"] == doc["id"] and source["calls"] == downloads

            def refresh(expected, reason=None):
                command = {"requestId": "refresh-" + uuid.uuid4().hex}
                run = call("POST", prefix + "/refresh", command, 202)
                same = call("POST", prefix + "/refresh", command)
                assert same["id"] == run["id"]
                records = poll(prefix + "/refresh-runs", lambda r: any(v["id"] == run["id"] and v["status"] in ("SUCCESS", "FAILED", "SKIPPED") for v in r["records"]))
                final = next(v for v in records["records"] if v["id"] == run["id"])
                assert final["status"] == expected, final
                if reason:
                    assert final["reason"] == reason, final
                return final

            refresh("SKIPPED", "HTTP_NOT_MODIFIED")
            source["etag"] = '"same-bytes-rotated-etag"'
            refresh("SKIPPED", "CONTENT_HASH_UNCHANGED")
            assert call("GET", prefix)["activeGeneration"] == before["activeGeneration"]
            source["body"] = "# Cooking\n\nBaking bread with flour."
            source["etag"] = '"v2"'
            success = refresh("SUCCESS", "CONTENT_ACTIVATED")
            after = call("GET", prefix)
            assert after["activeGeneration"] > before["activeGeneration"] and after["contentHash"] != before["contentHash"]
            hits = call("POST", "/api/knowledge/search", {"query": "bread", "kbIds": [kb["id"]], "documentIds": [doc["id"]]})
            assert hits and all(h["generation"] == after["activeGeneration"] for h in hits)
            assert "Baking bread" in hits[0]["content"]
            schedule = call("GET", prefix + "/refresh-schedule")
            configured = call("PUT", prefix + "/refresh-schedule", {"version": schedule["version"], "enabled": True, "cron": "*/2 * * * * *", "timezone": "Asia/Shanghai"})
            poll(prefix + "/refresh-runs", lambda r: any(v["triggerType"] == "SCHEDULED" and v["status"] == "SKIPPED" for v in r["records"]))
            schedule = call("GET", prefix + "/refresh-schedule")
            call("PUT", prefix + "/refresh-schedule", {"version": schedule["version"], "enabled": False, "cron": configured["cron"], "timezone": configured["timezone"]})
            source["fail"] = True
            refresh("FAILED", "KB_REMOTE_HTTP_ERROR")
            assert call("GET", prefix)["activeGeneration"] == after["activeGeneration"]
            schedule = call("GET", prefix + "/refresh-schedule")
            assert schedule["lastContentHash"] == after["contentHash"]
            evidence = {"environment": "Actual HTTP requests, controlled local origin; model and DB/S3 follow server configuration. No live provider claim.", "kbId": kb["id"], "documentId": doc["id"], "activatedRun": success, "evidence": hits, "calls": calls}
            output = os.environ.get("KB_REFRESH_EXAMPLE_OUTPUT")
            if output:
                Path(output).write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            print("PASS: URL import/replay, 304, hash skip, atomic source/generation switch, search, timezone schedule and finite failure recovery")
            print("Retained fixture KB:", kb["id"])
        finally:
            origin.shutdown()
            thread.join(timeout=5)


if __name__ == "__main__":
    main()
