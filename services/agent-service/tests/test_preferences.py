"""偏好来自 Java 运行快照；真实图验证每次模型调用只注入一次。"""

import json

import httpx
import pytest
from protocol_fixture import AUTH, ProtocolFixture
from test_agent import application

from agent_service.contracts import AgentError, ChatRequest


@pytest.mark.asyncio
async def test_preferences_are_fixed_during_turn_and_refresh_for_new_conversation(
    tmp_path,
):
    fixture = ProtocolFixture()
    fixture.preferences.update(
        revision=1, language="EN", detail="CONCISE", format="BULLETS"
    )
    original = fixture.java

    async def change_during_tool(request):
        if request.url.path == "/internal/rag/retrieve":
            fixture.preferences.update(
                revision=2, epoch=1, language="AUTO", detail="BALANCED", format="AUTO"
            )
        return await original(request)

    fixture.java = change_during_tool
    app = application(tmp_path, fixture)
    async with app.router.lifespan_context(app):
        runtime = app.state.runtime
        reply = await runtime.run(
            ChatRequest(question="备份方法"), httpx.BasicAuth(*AUTH), "a" * 32
        )
        assert reply["status"] == "COMPLETED" and len(fixture.model_requests) == 2
        for request in fixture.model_requests:
            backgrounds = [
                m
                for m in request["messages"]
                if m["role"] == "user" and "本轮用户回答偏好" in m["content"]
            ]
            assert len(backgrounds) == 1
            assert (
                "英语" in backgrounds[0]["content"]
                and "简洁" in backgrounds[0]["content"]
                and "项目符号" in backgrounds[0]["content"]
            )
            assert "tenantId" not in backgrounds[0]["content"]
        fixture.direct_answer = True
        await runtime.run(
            ChatRequest(question="新对话"), httpx.BasicAuth(*AUTH), "b" * 32
        )
        assert "本轮用户回答偏好" not in json.dumps(
            fixture.model_requests[-1]["messages"], ensure_ascii=False
        )
        assert {run["preferences"]["revision"] for run in fixture.runs.values()} == {
            1,
            2,
        }


@pytest.mark.asyncio
async def test_invalid_preference_snapshot_stops_before_any_model_call(tmp_path):
    fixture = ProtocolFixture()
    fixture.preferences["language"] = "IGNORE_AUTHORIZATION"
    app = application(tmp_path, fixture)
    async with app.router.lifespan_context(app):
        with pytest.raises(AgentError) as failure:
            await app.state.runtime.run(
                ChatRequest(question="备份方法"), httpx.BasicAuth(*AUTH), "c" * 32
            )
        assert failure.value.code == "INVALID_PREFERENCES"
        assert not fixture.model_requests
        assert next(iter(fixture.messages.values()))[-1]["status"] == "FAILED"
