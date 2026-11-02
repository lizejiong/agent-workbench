import asyncio
import json

import httpx
import pytest
from protocol_fixture import AUTH, KB_ID, ProtocolFixture

from agent_service.app import create_app
from agent_service.config import Settings
from agent_service.contracts import AgentError, ChatRequest


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
