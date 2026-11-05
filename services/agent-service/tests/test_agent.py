import asyncio
import json
import uuid
from dataclasses import replace

import httpx
import pytest
from protocol_fixture import AUTH, KB_ID, ProtocolFixture

from agent_service.app import create_app
from agent_service.config import Settings
from agent_service.contracts import AgentError, ChatRequest


@pytest.mark.asyncio
async def test_java_history_is_reused_and_duplicate_request_never_reexecutes(tmp_path):
    fixture = ProtocolFixture()
    app = application(tmp_path, fixture)
    events = []

    async def emit(name, data):
        events.append((name, data))

    async with app.router.lifespan_context(app):
        runtime = app.state.runtime
        request_id, message_id = uuid.uuid4(), uuid.uuid4()
        first = await runtime.run(
            ChatRequest(
                question="备份步骤", requestId=request_id, messageId=message_id
            ),
            httpx.BasicAuth(*AUTH),
            "a" * 32,
            emit,
        )
        assert sum(name == "delta" for name, _ in events) > 1
        saved = fixture.messages[first["conversationId"]][-1]
        assert saved["evidence"][0]["versions"]["pipeline"] == "fixture-v1"
        model_results = [
            json.loads(m["content"])
            for m in fixture.model_requests[1]["messages"]
            if m["role"] == "tool"
        ]
        assert all("versions" not in e for r in model_results for e in r["evidence"])
        assert events[0][0] == "meta" and events[-1][0] == "done"
        calls = len(fixture.model_requests)
        previous_settings = runtime.settings
        runtime.settings = replace(runtime.settings, model="new-config-after-deploy")
        repeated = await runtime.run(
            ChatRequest(
                question="备份步骤",
                conversationId=first["conversationId"],
                requestId=request_id,
                messageId=message_id,
            ),
            httpx.BasicAuth(*AUTH),
            "b" * 32,
        )
        assert repeated["replayed"] and repeated["answer"] == first["answer"]
        assert (
            repeated["runId"] == first["runId"] and len(fixture.model_requests) == calls
        )
        assert len(fixture.messages[first["conversationId"]]) == 2
        assert repeated["model"] == first["model"]
        runtime.settings = previous_settings
        fixture.direct_answer = True
        await runtime.run(
            ChatRequest(
                question="刚才的步骤再解释一下", conversationId=first["conversationId"]
            ),
            httpx.BasicAuth(*AUTH),
            "c" * 32,
        )
        model_history = fixture.model_requests[calls]["messages"]
        assert any(
            m["role"] == "assistant" and "按资料执行" in m["content"]
            for m in model_history
        )
        assert not any(m["role"] == "tool" for m in model_history)
        assert "[K" not in model_history[2]["content"]
        assert fixture.messages[first["conversationId"]][-1]["evidence"]
        assert not (tmp_path / "sessions.sqlite").exists()


@pytest.mark.asyncio
async def test_cancel_after_model_delta_preserves_partial_answer_in_java(tmp_path):
    fixture = ProtocolFixture()
    fixture.block_answer = True
    app = application(tmp_path, fixture)
    async with app.router.lifespan_context(app):
        runtime = app.state.runtime
        task = asyncio.create_task(
            runtime.run(
                ChatRequest(question="备份步骤"), httpx.BasicAuth(*AUTH), "d" * 32
            )
        )
        await asyncio.wait_for(fixture.partial_started.wait(), 5)
        await asyncio.sleep(0.03)
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        run = list(fixture.runs.values())[-1]
        assert run["status"] == "CANCELLED" and fixture.answer_closed.is_set()
        message = fixture.messages[run["conversationId"]][-1]
        assert message["content"] and message["evidence"] and message["tools"]
        assert message["status"] == "CANCELLED" and runtime.active == 0


@pytest.mark.asyncio
async def test_sse_disconnect_settles_partial_under_asgi_cancellation_scope(tmp_path):
    fixture = ProtocolFixture()
    fixture.block_answer = True
    cid = str(uuid.uuid4())
    fixture.conversations[cid] = {
        "id": cid,
        "owner": "alice",
        "title": "测试",
        "kbIds": [KB_ID],
        "documentIds": None,
    }
    fixture.messages[cid] = []
    app = application(tmp_path, fixture)
    request = httpx.Request(
        "POST",
        "http://test/api/agent/chat/stream",
        json={
            "conversationId": cid,
            "requestId": str(uuid.uuid4()),
            "messageId": str(uuid.uuid4()),
            "question": "备份",
        },
        headers={"Authorization": "Basic YWxpY2U6dGVzdC1wYXNzd29yZA=="},
    )
    scope = {
        "type": "http",
        "asgi": {"version": "3.0"},
        "http_version": "1.1",
        "method": "POST",
        "scheme": "http",
        "path": "/api/agent/chat/stream",
        "raw_path": b"/api/agent/chat/stream",
        "query_string": b"",
        "headers": [(k.lower(), v) for k, v in request.headers.raw],
        "client": ("127.0.0.1", 12345),
        "server": ("test", 80),
    }
    delivered = False
    sent = []

    async def receive():
        nonlocal delivered
        if not delivered:
            delivered = True
            return {"type": "http.request", "body": request.content, "more_body": False}
        await fixture.partial_started.wait()
        await asyncio.sleep(0.03)
        return {"type": "http.disconnect"}

    async def send(message):
        sent.append(message)

    async with app.router.lifespan_context(app):
        await asyncio.wait_for(app(scope, receive, send), 8)
        assert fixture.answer_closed.is_set() and app.state.runtime.active == 0
        assert fixture.messages[cid][-1]["content"]
        assert fixture.messages[cid][-1]["status"] == "CANCELLED"


def application(tmp_path, fixture, **overrides):
    settings = Settings(
        java_origin="http://127.0.0.1",
        service_id="agent-test",
        service_token="s" * 32,
        model_base_url="http://127.0.0.1/v1",
        model="fixture-agent",
        model_api_key="model-secret",
        state_dir=tmp_path,
        **overrides,
    )
    return create_app(
        settings,
        java_transport=httpx.MockTransport(fixture.java),
        model_transport=httpx.MockTransport(fixture.model),
    )


@pytest.mark.asyncio
async def test_graph_tools_delegate_identity_and_validate_unique_citations(
    tmp_path,
):
    fixture = ProtocolFixture()
    app = application(tmp_path, fixture)
    trace = "2123456789abcdef0123456789abcdef"
    async with app.router.lifespan_context(app):
        client_pool = app.state.runtime.gateway.client
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app), base_url="http://test"
        ) as client:
            response = await client.post(
                "/api/agent/chat",
                auth=AUTH,
                headers={"traceparent": f"00-{trace}-1234567890abcdef-01"},
                json={"question": "两次检索备份资料", "kbIds": [KB_ID]},
            )
            assert response.status_code == 200, response.text
            data = response.json()["data"]
            assert len(data["citations"]) == 2
            a, b = (c["evidenceId"] for c in data["citations"])
            assert a != b and f"[{a}]" in data["answer"] and f"[{b}]" in data["answer"]
            assert all(c["documentName"] == "备份说明" for c in data["citations"])
            assert (
                data["traceId"] == trace
                and response.headers["Cache-Control"] == "no-store"
            )
            assert {r["status"] for r in data["retrievals"]} == {"READY"}
            assert len(fixture.model_requests) == 2
            assert all(
                h["traceparent"].startswith(f"00-{trace}-")
                for h in fixture.model_headers
            )
            assert not {"tenantId", "userId", "query"} & data["citations"][0].keys()
            assert all(r.url.path != "/api/rag/answer" for r in fixture.java_requests)
            assert any(r.method == "DELETE" for r in fixture.java_requests)
            model_input = json.dumps(fixture.model_requests)
            assert (
                "model-secret" not in model_input
                and "delegationToken" not in model_input
            )
            assert app.state.runtime.gateway.client is client_pool
    assert client_pool.is_closed
    for file in tmp_path.glob("*.sqlite*"):
        assert b"model-secret" not in file.read_bytes()
        assert b"delegationToken" not in file.read_bytes()
        assert b"test-password" not in file.read_bytes()
        for token in fixture.grants:
            assert token.encode() not in file.read_bytes()


@pytest.mark.asyncio
async def test_session_restart_reauthenticates_and_drops_old_sensitive_context(
    tmp_path,
):
    fixture = ProtocolFixture()
    app = application(tmp_path, fixture)
    async with app.router.lifespan_context(app):
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app), base_url="http://test"
        ) as client:
            first = await client.post(
                "/api/agent/chat",
                auth=AUTH,
                json={"question": "备份步骤", "kbIds": [KB_ID]},
            )
            assert first.status_code == 200, first.text
            session_id = first.json()["data"]["sessionId"]
    before = len(fixture.model_requests)
    fixture.status = "NO_EVIDENCE"
    app = application(tmp_path, fixture)
    async with app.router.lifespan_context(app):
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app), base_url="http://test"
        ) as client:
            foreign = await client.post(
                "/api/agent/chat",
                auth=("bob", AUTH[1]),
                json={"question": "读取旧会话", "sessionId": session_id},
            )
            assert foreign.status_code == 404
            assert len(fixture.model_requests) == before
            second = await client.post(
                "/api/agent/chat",
                auth=AUTH,
                json={"question": "再确认一下", "sessionId": session_id},
            )
            assert second.status_code == 200, second.text
            new_input = fixture.model_requests[before]["messages"]
            assert [m["role"] for m in new_input] == ["system", "user", "user"]
            assert new_input[1]["content"] == "备份步骤"
            assert "授权资料：" not in json.dumps(new_input, ensure_ascii=False)
            assert second.json()["data"]["citations"] == []
            assert "资料不足" in second.json()["data"]["answer"]
            fixture.denied = True
            denied = await client.post(
                "/api/agent/chat",
                auth=AUTH,
                json={"question": "已撤权", "sessionId": session_id},
            )
            assert denied.status_code == 404


@pytest.mark.asyncio
async def test_auth_scope_statuses_and_sanitized_failures(tmp_path):
    fixture = ProtocolFixture()
    app = application(tmp_path, fixture)
    async with app.router.lifespan_context(app):
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app), base_url="http://test"
        ) as client:
            assert (
                await client.post("/api/agent/chat", json={"question": "问题"})
            ).status_code == 401
            bad = await client.post(
                "/api/agent/chat",
                auth=AUTH,
                json={"question": "问题", "tenantId": "other", "tools": []},
            )
            assert bad.status_code == 422
            for status, expected in [
                ("NO_EVIDENCE", "资料不足"),
                ("NEEDS_CLARIFICATION", "产品名称"),
            ]:
                fixture.status = status
                response = await client.post(
                    "/api/agent/chat", auth=AUTH, json={"question": "问题"}
                )
                assert response.status_code == 200, response.text
                assert expected in response.json()["data"]["answer"]
                assert response.json()["data"]["retrievals"][0]["status"] == status
            fixture.java_down = True
            down = await client.post(
                "/api/agent/chat", auth=AUTH, json={"question": "问题"}
            )
            assert down.json()["data"]["retrievals"][0]["status"] == "UNAVAILABLE"
            assert "暂不可用" in down.json()["data"]["answer"]
            assert "private-host" not in json.dumps(fixture.model_requests)
            fixture.java_down = False
            fixture.status = "READY"
            fixture.invalid_citation = True
            invalid = await client.post(
                "/api/agent/chat", auth=AUTH, json={"question": "问题"}
            )
            assert (
                invalid.status_code == 502
                and invalid.json()["code"] == "CITATION_INVALID"
            )
            fixture.model_down = True
            failed = await client.post(
                "/api/agent/chat", auth=AUTH, json={"question": "问题"}
            )
            assert failed.status_code == 503 and "secret" not in failed.text
            fixture.model_down = False
            fixture.invalid_citation = False
            fixture.truncate_answer = True
            truncated = await client.post(
                "/api/agent/chat", auth=AUTH, json={"question": "中途断流"}
            )
            assert (
                truncated.status_code == 502
                and truncated.json()["code"] == "AGENT_INCOMPLETE"
            )
            latest = list(fixture.messages.values())[-1][-1]
            assert latest["status"] == "FAILED" and latest["content"]


@pytest.mark.asyncio
async def test_cancel_closes_tool_http_revokes_grant_and_releases_capacity(tmp_path):
    fixture = ProtocolFixture()
    fixture.block_retrieval = True
    app = application(tmp_path, fixture, concurrency=1)
    async with app.router.lifespan_context(app):
        runtime = app.state.runtime
        auth = httpx.BasicAuth(*AUTH)
        task = asyncio.create_task(
            runtime.run(ChatRequest(question="备份"), auth, "1" * 32)
        )
        await asyncio.wait_for(fixture.started.wait(), 5)
        try:
            with pytest.raises(Exception) as busy:
                await runtime.run(ChatRequest(question="并发"), auth, "2" * 32)
            assert busy.value.code == "AGENT_CAPACITY"
        finally:
            task.cancel()
            with pytest.raises(asyncio.CancelledError):
                await task
        assert fixture.closed.is_set() and runtime.active == 0
        assert any(r.url.path.endswith("/cancel") for r in fixture.java_requests)
        assert any(r.method == "DELETE" for r in fixture.java_requests)
        session_id = next(iter(fixture.grants.values()))["agentSessionId"]
        fixture.block_retrieval = False
        result = await runtime.run(
            ChatRequest(question="新回合", sessionId=session_id), auth, "3" * 32
        )
        assert result["status"] == "COMPLETED"


@pytest.mark.asyncio
async def test_same_session_lease_and_deadline_cancel_pending_tool(tmp_path):
    fixture = ProtocolFixture()
    fixture.block_retrieval = True
    app = application(tmp_path, fixture, concurrency=2, turn_timeout=1)
    async with app.router.lifespan_context(app):
        runtime = app.state.runtime
        auth = httpx.BasicAuth(*AUTH)
        task = asyncio.create_task(
            runtime.run(ChatRequest(question="备份"), auth, "1" * 32)
        )
        try:
            await asyncio.wait_for(fixture.started.wait(), 5)
            session_id = next(iter(fixture.grants.values()))["agentSessionId"]
            with pytest.raises(AgentError) as busy:
                await runtime.run(
                    ChatRequest(question="并发", sessionId=session_id), auth, "2" * 32
                )
            assert busy.value.code == "SESSION_BUSY"
            with pytest.raises(AgentError) as timeout:
                await task
            assert timeout.value.code == "AGENT_TIMEOUT"
            assert timeout.value.status_code == 504
            assert fixture.closed.is_set() and runtime.active == 0
        finally:
            task.cancel()
            await asyncio.gather(task, return_exceptions=True)


@pytest.mark.asyncio
async def test_http_disconnect_cancels_graph_and_notifies_java(tmp_path):
    fixture = ProtocolFixture()
    fixture.block_retrieval = True
    app = application(tmp_path, fixture)
    request = httpx.Request(
        "POST",
        "http://test/api/agent/chat",
        json={"question": "备份"},
        headers={"Authorization": "Basic YWxpY2U6dGVzdC1wYXNzd29yZA=="},
    )
    scope = {
        "type": "http",
        "asgi": {"version": "3.0"},
        "http_version": "1.1",
        "method": "POST",
        "scheme": "http",
        "path": "/api/agent/chat",
        "raw_path": b"/api/agent/chat",
        "query_string": b"",
        "headers": [(key.lower(), value) for key, value in request.headers.raw],
        "client": ("127.0.0.1", 12345),
        "server": ("test", 80),
    }
    delivered = False
    sent = []

    async def receive():
        nonlocal delivered
        if not delivered:
            delivered = True
            return {"type": "http.request", "body": request.content, "more_body": False}
        await fixture.started.wait()
        return {"type": "http.disconnect"}

    async def send(message):
        sent.append(message)

    async with app.router.lifespan_context(app):
        await asyncio.wait_for(app(scope, receive, send), 5)
        assert fixture.closed.is_set() and app.state.runtime.active == 0, (
            sent,
            [r.url.path for r in fixture.java_requests],
            fixture.model_requests,
        )
        assert any(r.url.path.endswith("/cancel") for r in fixture.java_requests)
        assert any(r.method == "DELETE" for r in fixture.java_requests)
        assert (
            next(m["status"] for m in sent if m["type"] == "http.response.start") == 499
        )
