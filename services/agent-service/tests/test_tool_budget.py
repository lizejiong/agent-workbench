"""真实图观察完整模型请求及 Java 写入；外部 HTTP 使用明确的协议桩。"""

import json

import httpx
import pytest
from langchain.agents.middleware.types import ModelRequest
from langchain_core.messages import SystemMessage, convert_to_messages
from protocol_fixture import AUTH, ProtocolFixture
from test_agent import application

from agent_service.context.budget import RequestBudget
from agent_service.contracts import AgentError, ChatRequest
from agent_service.knowledge.adapter import digest, encode


@pytest.mark.asyncio
async def test_parallel_new_results_fit_remaining_window_and_persist_exact_excerpts(
    tmp_path,
):
    fixture = ProtocolFixture()
    fixture.reverse_tools = True
    fixture.tool_preamble = "过程说明。" + "p" * 18000
    fixture.evidence_content = (
        '授权资料：保留编号 A-42 与限制 "不要提交"，路径 \\backup。\n' * 700
    )
    app = application(tmp_path, fixture)
    async with app.router.lifespan_context(app):
        runtime = app.state.runtime
        reply = await runtime.run(
            ChatRequest(question="分两次查询备份要求，先不要提交"),
            httpx.BasicAuth(*AUTH),
            "a" * 32,
        )
        assert reply["status"] == "COMPLETED" and len(fixture.model_requests) == 2
        wire = fixture.model_requests[-1]
        results = [
            json.loads(m["content"]) for m in wire["messages"] if m["role"] == "tool"
        ]
        assert len(results) == 2
        assert all(r["status"] == "READY" and r["toolTruncated"] for r in results)
        assert all(len(encode(r)) < 2600 for r in results)
        assert results[0]["toolByteBudget"] == results[1]["toolByteBudget"]
        saved = fixture.messages[reply["conversationId"]][-1]
        snapshots = {s["evidenceId"]: s for s in saved["evidence"]}
        for result in results:
            for evidence in result["evidence"]:
                snapshot = snapshots[evidence["evidenceId"]]
                assert fixture.evidence_content.startswith(evidence["content"])
                assert snapshot["content"] == evidence["content"]
                assert snapshot["snapshotHash"] == digest(evidence["content"])
                assert snapshot["sourceContentHash"] == digest(fixture.evidence_content)
                assert snapshot["toolTruncated"]
        assert set(snapshots) == {c["evidenceId"] for c in reply["citations"]}
        req = ModelRequest(
            model=None,
            system_message=SystemMessage(wire["messages"][0]["content"]),
            messages=convert_to_messages(wire["messages"][1:]),
            tools=wire["tools"],
        )
        budget = RequestBudget(runtime.settings)
        assert budget.fits(budget.measure(req))
        assert any(
            "先不要提交" in m["content"]
            for m in wire["messages"]
            if m["role"] == "user"
        )


@pytest.mark.asyncio
async def test_tool_batch_without_room_for_status_fails_before_retrieval(tmp_path):
    fixture = ProtocolFixture()
    fixture.tool_preamble = "p" * 24500
    app = application(tmp_path, fixture)
    async with app.router.lifespan_context(app):
        with pytest.raises(AgentError) as error:
            await app.state.runtime.run(
                ChatRequest(question="请分两次检索备份要求"),
                httpx.BasicAuth(*AUTH),
                "b" * 32,
            )
        assert error.value.code == "CONTEXT_BUDGET"
        assert len(fixture.model_requests) == 1
        assert not any(
            r.url.path == "/internal/rag/retrieve" for r in fixture.java_requests
        )
        saved = next(iter(fixture.messages.values()))
        assert saved[0]["content"] == "请分两次检索备份要求"
        assert saved[-1]["status"] == "FAILED" and not saved[-1]["evidence"]
