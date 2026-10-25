#!/usr/bin/env python3
"""Stage 10 API-only delivery acceptance. Uses the configured backend/model; installs no mocks.

Runs only uniquely named fixtures. Some scripts retain their own KB for inspection.
Remote origin checks require KB_RUN_REMOTE_ACCEPTANCE=1 and an explicit approved local source policy.
Fault injection, tenant isolation and process restart are exercised by the Java integration suite.
"""
import base64
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid


def main():
    base = os.environ.get("KB_BASE_URL", "http://127.0.0.1:8080").rstrip("/")
    parsed = urllib.parse.urlsplit(base)
    if parsed.scheme not in ("http", "https") or not parsed.hostname or parsed.username or parsed.password:
        raise ValueError("KB_BASE_URL must be HTTP(S) without credentials")
    if parsed.scheme == "http" and parsed.hostname not in ("localhost", "127.0.0.1", "::1"):
        raise ValueError("Use HTTPS outside localhost")
    budget = int(os.environ.get("KB_ACCEPTANCE_WAIT_SECONDS", "300"))
    if not 1 <= budget <= 3600:
        raise ValueError("KB_ACCEPTANCE_WAIT_SECONDS must be 1..3600")
    token = base64.b64encode((os.environ["KB_USERNAME"] + ":" + os.environ["KB_PASSWORD"]).encode()).decode()

    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, *args, **kwargs):
            raise ValueError("Acceptance must not redirect credentials")

    opener = urllib.request.build_opener(NoRedirect())

    def call(method, path, body=None, expected=200, authenticated=True):
        headers = {"X-Request-Id": "delivery-" + uuid.uuid4().hex}
        if authenticated:
            headers["Authorization"] = "Basic " + token
        if body is not None:
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(base + path, None if body is None else json.dumps(body).encode(), headers, method=method)
        try:
            response = opener.open(request, timeout=20)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            payload = response.read(2 * 1024 * 1024)
            accepted = expected if isinstance(expected, tuple) else (expected,)
            if response.status not in accepted:
                raise AssertionError(f"{method} {path}: expected {accepted}, got {response.status}: {payload[:256]!r}")
            return json.loads(payload)

    entry = call("GET", "/api/knowledge/management")["data"]
    assert entry["delivery"] == "API_ONLY"
    call("GET", "/api/knowledge/management", expected=401, authenticated=False)
    collection = call("GET", entry["collection"])
    assert len(collection["item"]) >= 40
    call("GET", "/actuator/metrics", expected=401, authenticated=False)
    metrics = call("GET", "/actuator/metrics", expected=(200, 403))
    if "names" in metrics:
        assert {"kb.ingestion.jobs", "kb.ingestion.stage", "kb.refresh.runs", "kb.cleanup.resources"}.issubset(metrics["names"])
    call("GET", "/actuator/env", expected=403)
    assert call("GET", "/actuator/health", authenticated=False)["status"] == "UP"
    kb = call("POST", "/api/kbs", {"name": "delivery-entry-" + uuid.uuid4().hex}, expected=201)["data"]
    try:
        audit = call("GET", "/api/kbs/" + kb["id"] + "/audit")["data"]
        assert audit["total"] == 1 and audit["records"][0]["action"] == "BASE_INSERT"
        assert kb["name"] not in json.dumps(audit)
    finally:
        call("DELETE", "/api/kbs/" + kb["id"] + "?version=" + str(kb["version"]))

    directory = Path(__file__).resolve().parent
    scripts = ["acceptance.py", "upload_acceptance.py", "ingestion_acceptance.py", "search_acceptance.py",
               "maintenance_acceptance.py", "formats_acceptance.py", "hybrid_acceptance.py"]
    results = [{"script": "management-entry/collection/audit/metrics", "status": "PASS"}]
    if os.environ.get("KB_RUN_REMOTE_ACCEPTANCE") == "1":
        scripts.append("refresh_acceptance.py")
    else:
        results.append({"script": "refresh_acceptance.py", "status": "SKIPPED", "reason": "Set KB_RUN_REMOTE_ACCEPTANCE=1 only after approving its controlled local origin on the server"})
    for script in scripts:
        print("Running", script, flush=True)
        started = time.monotonic()
        result = subprocess.run([sys.executable, "-X", "utf8", str(directory / script)], env=os.environ.copy(),
                                capture_output=True, text=True, encoding="utf-8", timeout=min(3600, budget * 12 + 60))
        print(result.stdout[-6000:], end="", flush=True)
        if result.returncode:
            print(result.stderr[-3000:], file=sys.stderr, flush=True)
            raise AssertionError(f"{script} failed (exit {result.returncode}); inspect the owned fixture and backend stage logs")
        results.append({"script": script, "status": "PASS", "durationSeconds": round(time.monotonic() - started, 3)})
    report = {"result": "PASS", "checks": results,
              "environment": os.environ.get("KB_ACCEPTANCE_VECTOR_SOURCE", "Configured backend and model service; this script installs no model mocks"),
              "limitations": ["Some fixture KBs are retained and their IDs are printed by the individual scripts.",
                              "Run Maven verify for fault injection, lease fencing, process restart and cross-user/cross-tenant cases.",
                              "Live model quality, production networking and optional OCR require separate real-service validation."]}
    if os.environ.get("KB_DELIVERY_REPORT"):
        output = Path(os.environ["KB_DELIVERY_REPORT"])
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False))


if __name__ == "__main__":
    main()
