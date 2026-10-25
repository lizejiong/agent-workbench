#!/usr/bin/env python3
"""Stage 1 HTTP acceptance, stdlib only. Creates a unique KB and soft-deletes it on exit."""
import base64
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid


def main():
    base = os.environ.get("KB_BASE_URL", "http://127.0.0.1:8080").rstrip("/")
    parsed = urllib.parse.urlsplit(base)
    if parsed.scheme not in ("http", "https") or not parsed.hostname or parsed.username or parsed.password:
        raise ValueError("KB_BASE_URL must be an HTTP(S) origin without credentials")
    if parsed.scheme == "http" and parsed.hostname not in ("localhost", "127.0.0.1", "::1"):
        raise ValueError("Use HTTPS when sending credentials outside localhost")
    username, password = os.environ["KB_USERNAME"], os.environ["KB_PASSWORD"]
    token = base64.b64encode(f"{username}:{password}".encode("utf-8")).decode("ascii")
    api = "/api/v1/knowledge-bases"

    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            raise ValueError("Acceptance requests must not redirect credentials")

    opener = urllib.request.build_opener(NoRedirect())

    def call(method, path, body=None, expected=200, authenticated=True, envelope=True):
        headers = {"X-Request-Id": "acceptance-" + uuid.uuid4().hex}
        if authenticated:
            headers["Authorization"] = "Basic " + token
        if body is not None:
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(base + path, method=method, headers=headers,
                                         data=None if body is None else json.dumps(body).encode("utf-8"))
        try:
            response = opener.open(request, timeout=15)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            raw = response.read()
            if response.status != expected:
                raise AssertionError(f"{method} {path}: expected {expected}, received {response.status}")
            if not envelope:
                return raw
            result = json.loads(raw)
            if result["requestId"] != response.headers["X-Request-Id"]:
                raise AssertionError("Request ID mismatch")
            if expected < 300 and result["code"] != "0":
                raise AssertionError("Success response has non-success code")
            return result

    assert json.loads(call("GET", "/actuator/health", envelope=False, authenticated=False))["status"] == "UP"
    call("GET", api, expected=401, authenticated=False)
    assert b"openapi: 3.0.3" in call("GET", "/openapi/knowledge-base.yaml", envelope=False)
    name = "acceptance-" + uuid.uuid4().hex
    created = call("POST", api, {"name": name, "description": "HTTP acceptance"}, expected=201)["data"]
    path = api + "/" + created["id"]
    deleted = False
    try:
        assert call("GET", path)["data"]["version"] == 1
        page = call("GET", api + "?current=1&size=100")["data"]
        assert page["total"] >= 1
        assert any(item["id"] == created["id"] for item in page["records"])
        assert call("POST", api, {"name": name.upper()}, expected=409)["code"] == "KB_NAME_EXISTS"
        assert call("POST", api, {"name": "forged", "tenantId": "untrusted"}, expected=400)["code"] == "KB_INVALID_INPUT"
        updated = call("PUT", path, {"name": name, "description": "updated", "version": 1})["data"]
        assert updated["version"] == 2
        assert call("PUT", path, {"name": name, "version": 1}, expected=409)["code"] == "KB_VERSION_CONFLICT"
        call("DELETE", path + "?version=1", expected=409)
        call("DELETE", path + "?version=2")
        deleted = True
        call("GET", path, expected=404)
        print("PASS: health, auth, OpenAPI, CRUD, persisted reads, uniqueness, input ownership, version conflicts, deletion")
    finally:
        if not deleted:
            try:
                latest = call("GET", path)["data"]
                call("DELETE", path + "?version=" + str(latest["version"]))
            except Exception as error:
                print(f"Fixture cleanup failed ({type(error).__name__}); remove KB {created['id']} manually", file=sys.stderr)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"FAIL: {type(error).__name__}: {error}", file=sys.stderr)
        sys.exit(1)
