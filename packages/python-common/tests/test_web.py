import asyncio
import logging
import re

import h11
import httpx
import pytest
from fastapi import FastAPI, HTTPException, Request
from starlette.responses import PlainTextResponse, StreamingResponse

from rag_common import (
    ClientError,
    RemoteError,
    Result,
    ServiceError,
    get_request_id,
    install_web,
)
from rag_common.context import request_id_context
from rag_common.web import RequestContextMiddleware


@pytest.fixture
def app():
    application = FastAPI()
    install_web(application)

    @application.get("/success")
    async def success(request: Request):
        return Result.success({"state": request.state.request_id})

    @application.get("/sync")
    def sync():
        return Result.success(get_request_id())

    @application.get("/validated")
    async def validated(count: int):
        return Result.success(count)

    @application.get("/error/{kind}")
    async def error(kind: str):
        if kind == "client":
            raise ClientError("业务错误", code="A123456")
        if kind == "remote":
            raise RemoteError()
        if kind == "service":
            raise ServiceError()
        if kind == "http":
            raise HTTPException(
                429,
                "稍后再试",
                headers={"Retry-After": "3", "X-Request-Id": "conflict"},
            )
        if kind == "http-server":
            raise HTTPException(503, "维护中")
        raise RuntimeError("private-exception-secret")

    @application.post("/plain")
    async def plain():
        return PlainTextResponse("original")

    @application.get("/status/{status_code}")
    async def status_response(status_code: int):
        raise HTTPException(
            status_code,
            "status detail",
            headers={"X-Custom": "preserved", "X-Request-Id": "conflict"},
        )

    return application


@pytest.fixture
async def client(app):
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app, raise_app_exceptions=False),
        base_url="http://test",
    ) as session:
        yield session


async def test_success_and_sync_routes_get_context_and_state(client):
    for path in ("/success", "/sync"):
        response = await client.get(path, headers={"X-Request-Id": "request-123"})
        body = response.json()
        assert response.status_code == 200
        assert response.headers["x-request-id"] == body["requestId"] == "request-123"
        assert set(body) == {"code", "message", "data", "requestId"}
        assert body["data"] == (
            {"state": "request-123"} if path == "/success" else "request-123"
        )
        assert get_request_id() is None


@pytest.mark.parametrize(
    "path,status,code,message",
    [
        ("/validated?count=private-input", 422, "A000001", "请求参数校验失败"),
        ("/missing", 404, "A000001", "Not Found"),
        ("/plain", 405, "A000001", "Method Not Allowed"),
        ("/error/client", 400, "A123456", "业务错误"),
        ("/error/service", 500, "B000001", "系统执行错误"),
        ("/error/remote", 502, "C000001", "远程服务调用错误"),
        ("/error/http", 429, "A000001", "稍后再试"),
        ("/error/http-server", 503, "B000001", "维护中"),
        ("/error/unexpected", 500, "B000001", "系统执行错误"),
    ],
)
async def test_errors_have_matching_header_and_body_ids(
    client, path, status, code, message
):
    response = await client.get(path, headers={"X-Request-Id": "error-123"})
    assert response.status_code == status
    assert response.json() == {
        "code": code,
        "message": message,
        "data": None,
        "requestId": "error-123",
    }
    assert response.headers["x-request-id"] == "error-123"
    assert "private-" not in response.text
    if path == "/error/http":
        assert response.headers["retry-after"] == "3"
    assert get_request_id() is None


@pytest.mark.parametrize("status", [100, 101, 199, 204, 205, 304])
async def test_http_bodyless_statuses_preserve_headers_with_empty_body(app, status):
    messages = []
    await app(
        make_scope(
            path=f"/status/{status}",
            headers=[(b"x-request-id", b"bodyless-request")],
        ),
        AsyncReceive(),
        collect_into(messages),
    )
    start = messages[0]
    assert start["type"] == "http.response.start"
    assert start["status"] == status
    headers = dict(start["headers"])
    assert headers[b"x-custom"] == b"preserved"
    assert headers[b"x-request-id"] == b"bodyless-request"
    body_frames = messages[1:]
    assert body_frames
    assert all(frame["type"] == "http.response.body" for frame in body_frames)
    assert all(frame.get("body", b"") == b"" for frame in body_frames)
    assert not body_frames[-1].get("more_body", False)
    assert get_request_id() is None


@pytest.mark.parametrize("status", [204, 304])
async def test_bodyless_http_statuses_serialize_with_h11(app, status):
    messages = []
    await app(
        make_scope(path=f"/status/{status}"), AsyncReceive(), collect_into(messages)
    )
    connection = h11.Connection(h11.SERVER)
    connection.receive_data(b"GET / HTTP/1.1\r\nHost: test\r\n\r\n")
    assert isinstance(connection.next_event(), h11.Request)
    assert isinstance(connection.next_event(), h11.EndOfMessage)
    for message in messages:
        if message["type"] == "http.response.start":
            connection.send(
                h11.Response(status_code=message["status"], headers=message["headers"])
            )
        else:
            connection.send(h11.Data(data=message.get("body", b"")))
            if not message.get("more_body", False):
                connection.send(h11.EndOfMessage())
    assert connection.our_state is h11.DONE


@pytest.mark.parametrize("status", [200, 203, 206, 303, 305])
async def test_adjacent_http_statuses_keep_result_body(client, status):
    response = await client.get(f"/status/{status}")
    assert response.status_code == status
    assert response.json()["message"] == "status detail"
    assert response.json()["requestId"] == response.headers["x-request-id"]
    assert response.headers["x-custom"] == "preserved"


@pytest.mark.parametrize(
    "request_id", [None, "", "a" * 129, "has spaces", "bad\tvalue"]
)
async def test_missing_invalid_request_ids_generate_uuid(client, request_id):
    headers = {} if request_id is None else {"X-Request-Id": request_id}
    response = await client.get("/success", headers=headers)
    generated = response.headers["x-request-id"]
    assert re.fullmatch(r"[a-f0-9]{32}", generated)
    assert response.json()["requestId"] == generated


async def test_maximum_length_request_id_is_preserved(client):
    request_id = "A" * 122 + ".:_-09"
    assert len(request_id) == 128
    response = await client.get("/success", headers={"X-Request-Id": request_id})
    assert response.headers["x-request-id"] == request_id
    assert response.json()["requestId"] == request_id


@pytest.mark.parametrize("request_id", [None, "invalid value"])
async def test_new_request_does_not_inherit_callers_context(client, request_id):
    with request_id_context("outside"):
        headers = {} if request_id is None else {"X-Request-Id": request_id}
        response = await client.get("/success", headers=headers)
        assert response.json()["requestId"] != "outside"
        assert get_request_id() == "outside"
    assert get_request_id() is None


async def test_unexpected_error_generated_id_matches_after_context_reset(client):
    response = await client.get("/error/unexpected")
    assert re.fullmatch(r"[a-f0-9]{32}", response.headers["x-request-id"])
    assert response.json()["requestId"] == response.headers["x-request-id"]
    assert get_request_id() is None


async def test_body_validation_does_not_echo_input():
    from pydantic import BaseModel

    class Payload(BaseModel):
        count: int

    app = FastAPI()
    install_web(app)

    @app.post("/")
    async def endpoint(payload: Payload):
        return Result.success(payload.count)

    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app), base_url="http://test"
    ) as client:
        response = await client.post("/", json={"count": "private-body-input"})
    assert response.status_code == 422
    assert response.json()["message"] == "请求参数校验失败"
    assert "private-body-input" not in response.text


async def test_unicode_header_request_id_is_rejected_by_middleware():
    messages = []

    async def application(scope, receive, send):
        assert re.fullmatch(r"[a-f0-9]{32}", get_request_id())
        await send({"type": "http.response.start", "status": 204, "headers": []})
        await send({"type": "http.response.body", "body": b""})

    middleware = RequestContextMiddleware(application)
    await middleware(
        make_scope(headers=[(b"x-request-id", b"\xff")]),
        AsyncReceive(),
        collect_into(messages),
    )
    assert get_request_id() is None


async def test_concurrent_contexts_are_isolated():
    app = FastAPI()
    install_web(app)
    arrived = 0
    barrier = asyncio.Event()

    @app.get("/")
    async def concurrent():
        nonlocal arrived
        before = get_request_id()
        arrived += 1
        if arrived == 12:
            barrier.set()
        await barrier.wait()
        await asyncio.sleep(0)
        assert get_request_id() == before
        return Result.success(before)

    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app), base_url="http://test"
    ) as client:
        responses = await asyncio.wait_for(
            asyncio.gather(
                *(
                    client.get("/", headers={"X-Request-Id": f"id-{i}"})
                    for i in range(12)
                )
            ),
            timeout=5,
        )
    for i, response in enumerate(responses):
        assert response.json()["data"] == response.json()["requestId"] == f"id-{i}"
    assert get_request_id() is None


async def test_access_log_omits_query_headers_and_body(client, caplog):
    caplog.set_level(logging.INFO, logger="rag_common.web")
    response = await client.post(
        "/plain?token=query-secret",
        headers={"Authorization": "header-secret", "X-Request-Id": "safe-id"},
        content="body-secret",
    )
    assert response.text == "original"
    assert response.headers["x-request-id"] == "safe-id"
    records = [record for record in caplog.records if record.name == "rag_common.web"]
    assert len(records) == 1
    message = records[0].getMessage()
    for expected in ("POST", "/plain", "200", "duration_ms=", "safe-id"):
        assert expected in message
    for secret in ("query-secret", "header-secret", "body-secret", "Authorization"):
        assert secret not in message


async def test_unexpected_error_logs_diagnostic(client, caplog):
    caplog.set_level(logging.ERROR, logger="rag_common.web")
    response = await client.get("/error/unexpected")
    assert response.status_code == 500
    assert any(record.exc_info for record in caplog.records)


def make_scope(*, path="/stream", headers=None, scope_type="http"):
    return {
        "type": scope_type,
        "asgi": {"version": "3.0", "spec_version": "2.4"},
        "http_version": "1.1",
        "method": "GET",
        "scheme": "http",
        "path": path,
        "raw_path": path.encode(),
        "query_string": b"",
        "root_path": "",
        "headers": headers or [],
        "client": ("127.0.0.1", 1),
        "server": ("test", 80),
    }


class AsyncReceive:
    def __init__(self):
        self.called = False

    async def __call__(self):
        self.called = True
        raise AssertionError("Middleware must not read the request body")


def collect_into(messages):
    async def send(message):
        messages.append(message)

    return send


async def test_sse_stream_is_incremental_and_body_frames_unchanged():
    app = FastAPI()
    install_web(app)
    release = asyncio.Event()
    first_chunk_sent = asyncio.Event()
    messages = []

    @app.get("/stream")
    async def stream():
        async def chunks():
            yield b"event: token\ndata: first\n\n"
            await release.wait()
            yield b"data: second\n\n"

        return StreamingResponse(chunks(), media_type="text/event-stream")

    async def send(message):
        messages.append(message)
        if message["type"] == "http.response.body" and message.get("body"):
            first_chunk_sent.set()

    receive = AsyncReceive()
    task = asyncio.create_task(app(make_scope(), receive, send))
    await asyncio.wait_for(first_chunk_sent.wait(), 2)
    assert not task.done()
    assert [m["body"] for m in messages if m["type"] == "http.response.body"] == [
        b"event: token\ndata: first\n\n"
    ]
    release.set()
    await asyncio.wait_for(task, 2)
    assert [m for m in messages if m["type"] == "http.response.body"] == [
        {
            "type": "http.response.body",
            "body": b"event: token\ndata: first\n\n",
            "more_body": True,
        },
        {"type": "http.response.body", "body": b"data: second\n\n", "more_body": True},
        {"type": "http.response.body", "body": b"", "more_body": False},
    ]
    assert not receive.called
    assert get_request_id() is None


async def test_error_after_stream_start_propagates_without_second_response():
    app = FastAPI()
    install_web(app)
    messages = []

    @app.get("/stream")
    async def stream():
        async def chunks():
            yield b"first"
            raise RuntimeError("stream failed")

        return StreamingResponse(chunks())

    with pytest.raises(RuntimeError, match="stream failed"):
        await app(make_scope(), AsyncReceive(), collect_into(messages))
    assert len([m for m in messages if m["type"] == "http.response.start"]) == 1
    assert [m["body"] for m in messages if m["type"] == "http.response.body"] == [
        b"first"
    ]
    assert get_request_id() is None


async def test_cancellation_propagates_and_context_resets():
    app = FastAPI()
    install_web(app)
    messages = []

    @app.get("/stream")
    async def cancelled():
        assert get_request_id() is not None
        raise asyncio.CancelledError()

    with pytest.raises(asyncio.CancelledError):
        await app(make_scope(), AsyncReceive(), collect_into(messages))
    assert messages == []
    assert get_request_id() is None


@pytest.mark.parametrize("scope_type", ["lifespan", "websocket"])
async def test_non_http_scopes_pass_through(scope_type):
    scope = make_scope(scope_type=scope_type)
    messages = []

    async def downstream(actual_scope, receive, send):
        assert actual_scope is scope
        assert get_request_id() is None
        await send({"type": f"{scope_type}.close"})

    await RequestContextMiddleware(downstream)(
        scope, AsyncReceive(), collect_into(messages)
    )
    assert messages == [{"type": f"{scope_type}.close"}]
    assert "state" not in scope


def test_repeated_install_explicitly_rejected():
    app = FastAPI()
    install_web(app)
    with pytest.raises(RuntimeError, match="already"):
        install_web(app)


async def test_install_after_start_rejected():
    app = FastAPI()
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app), base_url="http://test"
    ) as client:
        await client.get("/")
    with pytest.raises(RuntimeError, match="before"):
        install_web(app)
