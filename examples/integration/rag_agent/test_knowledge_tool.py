import asyncio
import dataclasses
import inspect
import json
import time
import uuid
from datetime import UTC, datetime, timedelta

import httpx
import pytest
from knowledge_tool import (
    KnowledgeGateway,
    TrustedContext,
    TurnReferences,
    digest,
    encode,
)


def grant():
    return {
        "tenantId": "tenant-a",
        "userId": "user-a",
        "agentSessionId": str(uuid.uuid4()),
        "kbIds": [str(uuid.uuid4())],
        "documentIds": None,
        "delegationToken": "rd_" + "x" * 43,
        "expiresAt": (datetime.now(UTC) + timedelta(minutes=5)).isoformat(),
    }


def result(request, context, status="READY", text="使用 pg_dump 备份。", generation=1):
    return {
        "code": "0",
        "data": {
            "status": status,
            "requestId": request.headers["X-Request-Id"],
            "traceId": context.trace_id,
            "strategy": "VECTOR",
            "degraded": False,
            "rerankStatus": "DISABLED",
            "clarificationQuestion": "请补充产品名称。"
            if status == "NEEDS_CLARIFICATION"
            else None,
            "versions": {"pipeline": "retrieval-v2"},
            "contextHash": "c" * 64,
            "evidence": [
                {
                    "evidenceId": "E1",
                    "kbId": context.kb_ids[0],
                    "documentId": str(uuid.UUID(int=1)),
                    "chunkId": str(uuid.UUID(int=2)),
                    "generation": generation,
                    "documentName": "备份资料",
                    "content": text,
                    "sourceMetadata": {"pageNumber": 2},
                    "snapshotHash": digest(text),
                    "sourceContentHash": digest(text),
                    "truncated": False,
                    "adjacent": False,
                }
            ]
            if status == "READY"
            else [],
        },
    }


@pytest.mark.asyncio
async def test_query_only_tool_preserves_scope_and_gives_each_retrieval_unique_citations_with_bounded_text():
    context = TrustedContext.from_grant(grant())
    sent = []

    async def reply(request):
        sent.append(request)
        return httpx.Response(
            200,
            json=result(request, context, text="保留金额与单位，长资料正文。\n" * 1000),
        )

    async with httpx.AsyncClient(
        base_url="http://127.0.0.1", transport=httpx.MockTransport(reply)
    ) as client:
        references = TurnReferences()
        tool = KnowledgeGateway(client, "agent-test", "x" * 32).bind(
            context, references
        )
        assert list(inspect.signature(tool).parameters) == ["query"]
        one, two = await asyncio.gather(tool("备份方法？"), tool("恢复方法？"))
        assert one["status"] == two["status"] == "READY"
        a, b = one["evidence"][0], two["evidence"][0]
        assert a["localEvidenceId"] == b["localEvidenceId"] == "E1"
        assert a["evidenceId"] != b["evidenceId"]
        assert len(encode(one)) <= 4096 and one["toolTruncated"] and a["toolTruncated"]
        assert a["snapshotHash"] != a["toolContentHash"] == digest(a["content"])
        assert references.validate([a["evidenceId"], b["evidenceId"]])
        assert references.validate_answer(f"示例资料摘录 [{a['evidenceId']}]")
        with pytest.raises(ValueError):
            references.validate(["E1"])
        with pytest.raises(ValueError):
            references.validate_answer("错误局部引用 [E1]")
        for request in sent:
            body = json.loads(request.content)
            assert request.url.path == "/internal/rag/retrieve"
            assert body["kbIds"] == list(context.kb_ids)
            assert not (
                {"tenantId", "userId", "queryMode", "conversationId"} & body.keys()
            )
        assert "rd_" not in repr(context) and "rd_" not in str(one)
        # 宿主可与已有只读业务工具组合；这里只验证来源集合，不模拟主 Agent 决策。
        business = {"B_status": {"state": "available"}}
        combined = {**references.sources, **business}
        assert all(ref in combined for ref in [a["evidenceId"], "B_status"])


@pytest.mark.asyncio
async def test_no_evidence_clarification_dependency_permission_and_timeout_remain_distinct():
    context = TrustedContext.from_grant(grant())
    scenario = "NO_EVIDENCE"

    async def reply(request):
        if request.url.path.endswith("/cancel"):
            return httpx.Response(202, json={"code": "0"})
        if scenario == "TIMEOUT":
            await asyncio.sleep(1)
        if isinstance(scenario, int):
            return httpx.Response(
                scenario, text="SQL private password=http://internal/secret"
            )
        return httpx.Response(200, json=result(request, context, status=scenario))

    async with httpx.AsyncClient(
        base_url="http://127.0.0.1", transport=httpx.MockTransport(reply)
    ) as client:
        gateway = KnowledgeGateway(client, "agent-test", "x" * 32)
        for scenario in ["NO_EVIDENCE", "NEEDS_CLARIFICATION", "UNAVAILABLE", 503, 403]:
            value = await gateway.search("独立问题", context, TurnReferences())
            assert value["status"] == (
                scenario if isinstance(scenario, str) else "UNAVAILABLE"
            )
            assert not value["evidence"]
            assert "private" not in str(value) and "password" not in str(value)
            if scenario == 403:
                assert value["errorCode"] == "ACCESS_DENIED" and not value["retryable"]
            if scenario == "NEEDS_CLARIFICATION":
                assert value["clarificationQuestion"] == "请补充产品名称。"
        scenario = "TIMEOUT"
        value = await gateway.search(
            "独立问题",
            dataclasses.replace(context, deadline=time.monotonic() + 0.03),
            TurnReferences(),
        )
        assert (
            value["status"] == "UNAVAILABLE"
            and value["errorCode"] == "DEADLINE_EXCEEDED"
        )


@pytest.mark.asyncio
async def test_cancellation_closes_response_stream_and_notifies_java_then_propagates_cancelled_error():
    entered, closed = asyncio.Event(), asyncio.Event()
    notified = []
    context = TrustedContext.from_grant(grant())

    class Blocking(httpx.AsyncByteStream):
        async def __aiter__(self):
            entered.set()
            await asyncio.sleep(100)
            yield b""

        async def aclose(self):
            closed.set()

    async def reply(request):
        if request.url.path.endswith("/cancel"):
            notified.append(request)
            return httpx.Response(202)
        return httpx.Response(200, stream=Blocking())

    async with httpx.AsyncClient(
        base_url="http://127.0.0.1", transport=httpx.MockTransport(reply)
    ) as client:
        gateway = KnowledgeGateway(client, "agent-test", "x" * 32)
        task = asyncio.create_task(
            gateway.search("独立问题", context, TurnReferences())
        )
        await entered.wait()
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        assert closed.is_set() and len(notified) == 1


@pytest.mark.asyncio
async def test_restored_reference_requires_current_exact_generation_and_fresh_runtime():
    context = TrustedContext.from_grant(grant())
    generation = 1
    calls = 0

    async def reply(request):
        nonlocal calls
        calls += 1
        return httpx.Response(200, json=result(request, context, generation=generation))

    async with httpx.AsyncClient(
        base_url="http://127.0.0.1", transport=httpx.MockTransport(reply)
    ) as client:
        gateway = KnowledgeGateway(client, "agent-test", "x" * 32)
        references = TurnReferences()
        value = await gateway.search("独立问题", context, references)
        saved = references.validate([value["evidence"][0]["evidenceId"]])[0]
        assert "content" not in saved and "delegationToken" not in saved
        assert await gateway.reconfirm(saved, context, TurnReferences())
        generation = 2
        restored = TurnReferences()
        assert await gateway.reconfirm(saved, context, restored) is None
        assert not restored.sources
        before = calls
        expired = dataclasses.replace(context, deadline=time.monotonic() - 1)
        assert (await gateway.search("独立问题", expired, TurnReferences()))[
            "status"
        ] == "UNAVAILABLE"
        assert calls == before
        assert (
            await gateway.reconfirm(
                saved,
                dataclasses.replace(context, user_id="another-user"),
                TurnReferences(),
            )
            is None
        )
        assert calls == before
