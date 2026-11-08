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
async def test_history_reserves_current_question_preferences_and_fixed_request(
    tmp_path,
):
    fixture = ProtocolFixture()
    fixture.direct_answer = True
    app = application(tmp_path, fixture)
    async with app.router.lifespan_context(app):
        runtime = app.state.runtime
        conversation = None
        for index in range(3):
            reply = await runtime.run(
                ChatRequest(question=f"旧问题{index}", conversationId=conversation),
                httpx.BasicAuth(*AUTH),
                "a" * 32,
            )
            conversation = reply["conversationId"]
        assistants = [
            row for row in fixture.messages[conversation] if row["role"] == "assistant"
        ]
        for index, row in enumerate(assistants):
            row["content"] = f"完整旧答复{index}：" + "h" * 5000
        fixture.preferences.update(revision=1, language="EN", detail="CONCISE")
        question = "当前问题：先不要提交，编号 A-42。" + "新" * 2850
        reply = await runtime.run(
            ChatRequest(question=question, conversationId=conversation),
            httpx.BasicAuth(*AUTH),
            "b" * 32,
        )
        assert reply["status"] == "COMPLETED"
        wire = fixture.model_requests[-1]
        messages = wire["messages"]
        assert messages[-1]["content"] == question
        retained = [m["content"] for m in messages if m["role"] == "assistant"]
        assert 1 <= len(retained) < len(assistants)
        assert [m["role"] for m in messages] == (
            ["system", "user"] + ["user", "assistant"] * len(retained) + ["user"]
        )
        assert retained == [row["content"] for row in assistants[-len(retained) :]]
        assert "本轮用户回答偏好" in messages[1]["content"]
        budget = RequestBudget(runtime.settings)
        request = ModelRequest(
            model=None,
            system_message=SystemMessage(messages[0]["content"]),
            messages=convert_to_messages(messages[1:]),
            tools=wire["tools"],
        )
        assert budget.fits(budget.measure(request))


@pytest.mark.asyncio
async def test_latest_complete_turn_still_obeys_full_request_hard_limit(tmp_path):
    fixture = ProtocolFixture()
    fixture.direct_answer = True
    app = application(tmp_path, fixture)
    async with app.router.lifespan_context(app):
        runtime = app.state.runtime
        first = await runtime.run(
            ChatRequest(question="先不要提交，编号 A-42"),
            httpx.BasicAuth(*AUTH),
            "d" * 32,
        )
        rows = fixture.messages[first["conversationId"]]
        rows[-1]["content"] = "h" * 22000
        before = len(fixture.model_requests)
        with pytest.raises(AgentError) as failure:
            await runtime.run(
                ChatRequest(question="继续", conversationId=first["conversationId"]),
                httpx.BasicAuth(*AUTH),
                "e" * 32,
            )
        assert failure.value.code == "CONTEXT_BUDGET"
        assert len(fixture.model_requests) == before
        assert rows[1]["content"] == "h" * 22000
        assert rows[-2]["content"] == "继续" and rows[-1]["status"] == "FAILED"


@pytest.mark.asyncio
async def test_compact_tool_view_keeps_full_excerpt_and_server_provenance(tmp_path):
    fixture = ProtocolFixture()
    fixture.evidence_content = (
        "适用编号 A-42，金额 300 元。\n" + "x" * 3000 + "\n例外：未经确认不得提交。"
    )
    app = application(tmp_path, fixture)
    async with app.router.lifespan_context(app):
        reply = await app.state.runtime.run(
            ChatRequest(question="备份要求及例外条件"),
            httpx.BasicAuth(*AUTH),
            "c" * 32,
        )
        result = next(
            json.loads(message["content"])
            for message in fixture.model_requests[-1]["messages"]
            if message["role"] == "tool"
        )
        evidence = result["evidence"][0]
        assert evidence["content"] == fixture.evidence_content
        assert not result["toolTruncated"] and len(encode(result)) <= 4096
        assert set(evidence) == {
            "evidenceId",
            "documentName",
            "content",
            "sourceMetadata",
            "truncated",
            "toolTruncated",
            "adjacent",
        }
        assert (
            not {
                "requestId",
                "traceId",
                "contextHash",
                "versionFingerprint",
                "strategy",
                "rerankStatus",
                "toolByteBudget",
                "estimated",
            }
            & result.keys()
        )
        saved = fixture.messages[reply["conversationId"]][-1]
        snapshot = saved["evidence"][0]
        assert snapshot["content"] == evidence["content"]
        assert snapshot["snapshotHash"] == digest(evidence["content"])
        assert snapshot["sourceContentHash"] == digest(fixture.evidence_content)
        assert snapshot["kbId"] and snapshot["documentId"] and snapshot["chunkId"]
        assert snapshot["generation"] == 1
        assert snapshot["versions"] == {"pipeline": "fixture-v1"}
        assert snapshot["sourceMetadata"] == evidence["sourceMetadata"]
        assert reply["citations"][0]["evidenceId"] == evidence["evidenceId"]
        assert reply["citations"][0]["documentId"] == snapshot["documentId"]


@pytest.mark.asyncio
async def test_parallel_new_results_fit_remaining_window_and_persist_exact_excerpts(
    tmp_path,
):
    fixture = ProtocolFixture()
    fixture.reverse_tools = True
    fixture.tool_preamble = "过程说明。" + "p" * 17000
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
        assert all("toolByteBudget" not in r for r in results)
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
