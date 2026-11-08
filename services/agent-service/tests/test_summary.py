"""真实 create_agent + HTTP 协议桩；不声明供应商的摘要语义效果。"""

import asyncio
import json
from dataclasses import replace

import httpx
import pytest
from protocol_fixture import AUTH, ProtocolFixture
from test_agent import application

from agent_service.contracts import AgentError, ChatRequest
from agent_service.knowledge.adapter import encode


async def seed(runtime, fixture):
    reply = await runtime.run(
        ChatRequest(question="先不要提交，编号 A-42"), httpx.BasicAuth(*AUTH), "a" * 32
    )
    fixture.direct_answer = True
    await runtime.run(
        ChatRequest(question="继续分析", conversationId=reply["conversationId"]),
        httpx.BasicAuth(*AUTH),
        "b" * 32,
    )
    return reply["conversationId"]


@pytest.mark.asyncio
async def test_summary_keeps_latest_complete_turn_above_soft_target(tmp_path):
    fixture = ProtocolFixture()
    fixture.direct_answer = True
    app = application(tmp_path, fixture, summary_enabled=True)
    async with app.router.lifespan_context(app):
        runtime = app.state.runtime
        conversation = None
        for index in range(3):
            reply = await runtime.run(
                ChatRequest(
                    question=f"问题{index}：先不要提交，编号 A-42",
                    conversationId=conversation,
                ),
                httpx.BasicAuth(*AUTH),
                "a" * 32,
            )
            conversation = reply["conversationId"]
        assistants = [
            row for row in fixture.messages[conversation] if row["role"] == "assistant"
        ]
        for index, row in enumerate(assistants):
            row["content"] = f"完整答复{index}：" + "h" * 6000
        before = len(fixture.model_requests)
        reply = await runtime.run(
            ChatRequest(question="按原限制继续", conversationId=conversation),
            httpx.BasicAuth(*AUTH),
            "b" * 32,
        )
        assert reply["status"] == "COMPLETED"
        assert len(fixture.model_requests) - before == 2
        messages = fixture.model_requests[-1]["messages"]
        assert [m["content"] for m in messages if m["role"] == "assistant"] == [
            assistants[-1]["content"]
        ]
        assert any("历史会话摘要" in m["content"] for m in messages)
        assert fixture.runs[reply["runId"]]["summaryRevision"] == 1
        assert len(fixture.messages[conversation]) == 8


@pytest.mark.asyncio
async def test_oversized_summary_combination_falls_back_without_binding(tmp_path):
    fixture = ProtocolFixture()
    fixture.direct_answer = True
    original = fixture.model

    async def large_summary(request):
        response = await original(request)
        body = json.loads(request.content)
        if body.get("tools"):
            return response
        data = response.json()
        message = data["choices"][0]["message"]
        content = json.loads(message["content"])
        source = json.loads(body["messages"][-1]["content"])
        source_id = source["turns"][0]["messages"][0]["id"]
        content["facts"] = [
            {"text": "v" * 1000, "sourceMessageIds": [source_id]} for _ in range(7)
        ]
        assert len(encode(content)) <= 8192
        message["content"] = json.dumps(content, ensure_ascii=False)
        return httpx.Response(200, json=data)

    fixture.model = large_summary
    app = application(tmp_path, fixture, summary_enabled=True, history_turns=1)
    async with app.router.lifespan_context(app):
        runtime = app.state.runtime
        conversation = await seed(runtime, fixture)
        latest = fixture.messages[conversation][-1]
        latest["content"] = "完整近期答复：" + "h" * 6000
        before = len(fixture.model_requests)
        question = "继续，先不要提交。" + "新" * 2800
        reply = await runtime.run(
            ChatRequest(question=question, conversationId=conversation),
            httpx.BasicAuth(*AUTH),
            "e" * 32,
        )
        assert reply["status"] == "COMPLETED"
        assert len(fixture.model_requests) - before == 2
        assert len(fixture.summary_records[conversation]) == 1
        messages = fixture.model_requests[-1]["messages"]
        assert messages[-1]["content"] == question
        assert [m["content"] for m in messages if m["role"] == "assistant"] == [
            latest["content"]
        ]
        assert not any("历史会话摘要" in m["content"] for m in messages)
        assert "summaryRevision" not in fixture.runs[reply["runId"]]
        assert not any(
            r.url.path.endswith("/context-summary/use") for r in fixture.java_requests
        )


@pytest.mark.asyncio
async def test_durable_summary_inherits_evidence_and_stays_out_of_answer_stream(
    tmp_path,
):
    fixture = ProtocolFixture()
    app = application(tmp_path, fixture, summary_enabled=True, history_turns=1)
    events = []

    async def emit(name, data):
        events.append((name, data))

    async with app.router.lifespan_context(app):
        runtime = app.state.runtime
        conversation = await seed(runtime, fixture)
        before = len(fixture.model_requests)
        reply = await runtime.run(
            ChatRequest(question="按原限制继续", conversationId=conversation),
            httpx.BasicAuth(*AUTH),
            "c" * 32,
            emit,
            protocol_version=2,
        )
        summary_call, answer_call = fixture.model_requests[before:]
        assert not summary_call.get("tools") and not summary_call["stream"]
        assert answer_call["tools"] and "A-42" in json.dumps(
            answer_call["messages"], ensure_ascii=False
        )
        record = fixture.summary_records[conversation][0]
        assert (
            record["throughSequence"] == 2
            and record["content"]["constraints"][0]["text"] == "先不要提交，编号 A-42"
        )
        assert fixture.runs[reply["runId"]]["summaryRevision"] == 1
        assert (
            fixture.messages[conversation][-1]["evidence"]
            == fixture.messages[conversation][1]["evidence"]
        )
        assert (
            len(fixture.messages[conversation]) == 6
        )  # 原始消息和知识快照未被摘要替换。
        assert "整理备份方案" not in json.dumps(events, ensure_ascii=False)
        assert fixture.model_headers[before]["traceparent"].split("-")[1] == "c" * 32
    # 新建运行实例只从 Java 摘要和原始历史重建；不依靠旧图或 Python 内存。
    restarted = application(tmp_path, fixture, summary_enabled=True, history_turns=1)
    async with restarted.router.lifespan_context(restarted):
        await restarted.state.runtime.run(
            ChatRequest(question="继续下一步", conversationId=conversation),
            httpx.BasicAuth(*AUTH),
            "d" * 32,
        )
        revisions = fixture.summary_records[conversation]
        assert [r["throughSequence"] for r in revisions] == [2, 4]
        assert (
            revisions[1]["content"]["constraints"][0]
            == revisions[0]["content"]["constraints"][0]
        )


@pytest.mark.asyncio
@pytest.mark.parametrize("mode", ["invalid", "timeout", "budget"])
async def test_bad_or_timed_out_summary_preserves_history_and_watermark(tmp_path, mode):
    fixture = ProtocolFixture()
    app = application(
        tmp_path, fixture, summary_enabled=True, history_turns=1, summary_timeout=1
    )
    async with app.router.lifespan_context(app):
        runtime = app.state.runtime
        conversation = await seed(runtime, fixture)
        fixture.block_summary = mode == "timeout"
        fixture.invalid_summary = mode == "invalid"
        if mode == "budget":
            runtime.settings = replace(runtime.settings, max_model_calls=1)
        before = len(fixture.model_requests)
        reply = await runtime.run(
            ChatRequest(question="继续", conversationId=conversation),
            httpx.BasicAuth(*AUTH),
            "d" * 32,
        )
        assert reply["status"] == "COMPLETED"
        assert (
            not fixture.summary_records[conversation]
            and len(fixture.messages[conversation]) == 6
        )
        assert "历史会话摘要" not in json.dumps(
            fixture.model_requests[-1]["messages"], ensure_ascii=False
        )
        assert len(fixture.model_requests) - before == (1 if mode == "budget" else 2)
        if mode == "timeout":
            assert fixture.summary_closed.is_set()


@pytest.mark.asyncio
async def test_cancel_during_summary_cancels_http_without_committing_or_answering(
    tmp_path,
):
    fixture = ProtocolFixture()
    app = application(tmp_path, fixture, summary_enabled=True, history_turns=1)
    async with app.router.lifespan_context(app):
        runtime = app.state.runtime
        conversation = await seed(runtime, fixture)
        fixture.block_summary = True
        before = len(fixture.model_requests)
        task = asyncio.create_task(
            runtime.run(
                ChatRequest(question="继续", conversationId=conversation),
                httpx.BasicAuth(*AUTH),
                "f" * 32,
            )
        )
        try:
            await asyncio.wait_for(fixture.summary_started.wait(), 3)
            run = next(r for r in fixture.runs.values() if r["status"] == "RUNNING")
            run["status"] = "CANCELLED"
            with pytest.raises(AgentError) as error:
                await asyncio.wait_for(task, 3)
            assert error.value.code == "AGENT_RUN_ENDED"
            assert (
                fixture.summary_closed.is_set()
                and not fixture.summary_records[conversation]
            )
            assert len(fixture.model_requests) == before + 1
            assert fixture.messages[conversation][-1]["status"] == "CANCELLED"
        finally:
            task.cancel()
            await asyncio.gather(task, return_exceptions=True)
