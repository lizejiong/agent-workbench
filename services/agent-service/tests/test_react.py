"""真实 LangGraph + ChatOpenAI HTTP 协议，Java 是显式协议桩；不是供应商效果验证。"""

import asyncio
import json

import httpx
import pytest
from protocol_fixture import AUTH, ProtocolFixture
from test_agent import application

from agent_service.contracts import AgentError, ChatRequest


class ScriptedFixture(ProtocolFixture):
    def __init__(self, plans):
        super().__init__()
        self.plans = plans
        self.step = 0
        self.reject_tools = False
        self.final_text = None

    async def model(self, request):
        body = json.loads(request.content)
        self.model_requests.append(body)
        if self.reject_tools:
            return httpx.Response(400, json={"error": {"message": "tools unsupported"}})
        if body.get("stream"):
            results = [
                json.loads(m["content"])
                for m in body["messages"]
                if m["role"] == "tool"
            ]
            refs = list(
                dict.fromkeys(
                    e for result in results for e in result.get("evidenceRefs", [])
                )
            )
            text = self.final_text or "根据本轮来源给出限定回答。" + "".join(
                f"[{e}]" for e in refs
            )
            owner = self

            class Frames(httpx.AsyncByteStream):
                async def __aiter__(self):
                    try:
                        for offset in range(0, len(text), 8):
                            frame = {
                                "id": "final-fixture",
                                "object": "chat.completion.chunk",
                                "created": 1,
                                "model": "fixture-agent",
                                "choices": [
                                    {
                                        "index": 0,
                                        "delta": {"content": text[offset : offset + 8]},
                                        "finish_reason": None,
                                    }
                                ],
                            }
                            yield ("data: " + json.dumps(frame) + "\n\n").encode()
                            if owner.block_answer:
                                owner.partial_started.set()
                                await asyncio.Event().wait()
                        frame["choices"] = [
                            {"index": 0, "delta": {}, "finish_reason": "stop"}
                        ]
                        yield ("data: " + json.dumps(frame) + "\n\n").encode()
                        yield b"data: [DONE]\n\n"
                    finally:
                        owner.answer_closed.set()

            return httpx.Response(
                200, headers={"Content-Type": "text/event-stream"}, stream=Frames()
            )
        plan = self.plans[min(self.step, len(self.plans) - 1)]
        self.step += 1
        if callable(plan):
            plan = plan(body)
        calls = [
            {
                "id": item.get("id", f"vendor-{self.step}-{index}"),
                "type": "function",
                "function": {
                    "name": item["name"],
                    "arguments": item["args"]
                    if isinstance(item["args"], str)
                    else json.dumps(item["args"]),
                },
            }
            for index, item in enumerate(plan)
        ]
        message = {
            "role": "assistant",
            "content": None,
            **({"tool_calls": calls} if calls else {}),
        }
        return httpx.Response(
            200,
            json={
                "id": f"planning-{self.step}",
                "object": "chat.completion",
                "created": 1,
                "model": "fixture-agent",
                "choices": [
                    {
                        "index": 0,
                        "message": message,
                        "finish_reason": "tool_calls" if calls else "stop",
                    }
                ],
            },
        )


def search(query="备份", **extras):
    return {"name": "search_knowledge", "args": {"query": query, **extras}}


async def run(app, *, events=None):
    async def emit(name, data):
        events.append((name, data))

    return await app.state.runtime.run(
        ChatRequest(question="请查验本轮资料", mode="agent"),
        httpx.BasicAuth(*AUTH),
        "a" * 32,
        emit if events is not None else None,
        protocol_version=3,
    )


@pytest.mark.asyncio
async def test_stategraph_multi_round_parallel_calls_keep_pairs_and_v3_timeline(
    tmp_path,
):
    fixture = ScriptedFixture([[search("0"), search("1")], [search("第三次")], []])
    fixture.reverse_tools = True
    app = application(tmp_path, fixture)
    events = []
    async with app.router.lifespan_context(app):
        reply = await run(app, events=events)
    assert reply["status"] == "COMPLETED" and len(reply["citations"]) == 3
    assert len({e["evidenceId"] for e in reply["citations"]}) == 3
    assert len(fixture.tool_calls) == 3
    assert all(
        call["result"]["status"] == "success" for call in fixture.tool_calls.values()
    )
    assert [data["seq"] for _, data in events] == list(range(1, len(events) + 1))
    assert [name for name, _ in events][-2:] == ["finish", "done"]
    assert any(name == "tool.success" for name, _ in events)
    assert all(len(json.dumps(data).encode()) < 65536 for _, data in events)
    for request in fixture.model_requests:
        pending = set()
        for message in request["messages"]:
            pending.update(c["id"] for c in message.get("tool_calls", []))
            if message["role"] == "tool":
                pending.remove(message["tool_call_id"])
        assert not pending
    assert all(not request.get("stream") for request in fixture.model_requests[:-1])
    assert fixture.model_requests[-1]["stream"]


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "plan,expected",
    [
        ([search(actor="forged")], "invalid_arguments"),
        ([{"name": "search_knowledge", "args": "{bad-json"}], "invalid_arguments"),
        ([{"name": "unpublished_tool", "args": {}}], "denied"),
    ],
)
async def test_bad_calls_are_paired_audited_and_cannot_expand_identity(
    tmp_path, plan, expected
):
    fixture = ScriptedFixture([plan, []])
    app = application(tmp_path, fixture)
    events = []
    async with app.router.lifespan_context(app):
        reply = await run(app, events=events)
    assert reply["status"] == "COMPLETED" and not reply["citations"]
    assert list(fixture.tool_calls.values())[0]["result"]["status"] == expected
    assert not any(
        r.url.path == "/internal/rag/retrieve" for r in fixture.java_requests
    )
    assert any(n == "tool.failure" and d["status"] == expected for n, d in events)
    assert "forged" not in json.dumps(reply["citations"])


@pytest.mark.asyncio
async def test_evidence_read_reauthenticates_and_only_accepts_run_evidence(tmp_path):
    def read_evidence(body):
        result = next(
            json.loads(m["content"]) for m in body["messages"] if m["role"] == "tool"
        )
        return [
            {"name": "evidence_read", "args": {"evidenceId": result["evidenceRefs"][0]}}
        ]

    fixture = ScriptedFixture([[search()], read_evidence, []])
    app = application(tmp_path, fixture)
    async with app.router.lifespan_context(app):
        reply = await run(app)
    assert reply["status"] == "COMPLETED"
    assert any(r.url.path.endswith("/evidence") for r in fixture.java_requests)
    assert len(fixture.tool_calls) == 2


@pytest.mark.asyncio
async def test_budget_pairs_every_call_then_answers_from_available_sources(tmp_path):
    fixture = ScriptedFixture([[search("0"), search("1"), search("2")]])
    app = application(tmp_path, fixture, max_tool_calls=1, max_model_calls=2)
    events = []
    async with app.router.lifespan_context(app):
        reply = await run(app, events=events)
    assert len(fixture.tool_calls) == 1 and len(reply["citations"]) == 1
    assert len(fixture.model_requests) == 2
    tool_messages = [
        m for m in fixture.model_requests[-1]["messages"] if m["role"] == "tool"
    ]
    assert len(tool_messages) == 3
    assert (
        sum(
            json.loads(m["content"])["errorCode"] == "TOOL_CALL_BUDGET"
            for m in tool_messages
        )
        == 2
    )


@pytest.mark.asyncio
async def test_repeated_provider_id_never_executes_twice(tmp_path):
    call = {**search(), "id": "same-vendor-id"}
    fixture = ScriptedFixture([[call], [call], []])
    app = application(tmp_path, fixture)
    async with app.router.lifespan_context(app):
        reply = await run(app)
    assert reply["status"] == "COMPLETED" and len(fixture.tool_calls) == 1
    assert (
        sum(r.url.path == "/internal/rag/retrieve" for r in fixture.java_requests) == 1
    )


@pytest.mark.asyncio
async def test_tools_unsupported_is_an_explicit_error(tmp_path):
    fixture = ScriptedFixture([[search()]])
    fixture.reject_tools = True
    app = application(tmp_path, fixture)
    async with app.router.lifespan_context(app):
        with pytest.raises(AgentError) as error:
            await run(app)
    assert error.value.code == "MODEL_TOOLS_UNSUPPORTED"
    assert not fixture.tool_calls


@pytest.mark.asyncio
async def test_stop_cancels_real_model_stream_and_settles_history(tmp_path):
    fixture = ScriptedFixture([[search()], []])
    fixture.block_answer = True
    app = application(tmp_path, fixture)
    async with app.router.lifespan_context(app):
        task = asyncio.create_task(run(app))
        await asyncio.wait_for(fixture.partial_started.wait(), 5)
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        assert fixture.answer_closed.is_set()
        assert next(iter(fixture.runs.values()))["status"] == "CANCELLED"


@pytest.mark.asyncio
async def test_empty_authorized_toolset_is_real_unavailable(tmp_path):
    fixture = ScriptedFixture([[]])
    fixture.tool_definitions = []
    app = application(tmp_path, fixture)
    async with app.router.lifespan_context(app):
        with pytest.raises(AgentError) as error:
            await run(app)
    assert error.value.code == "TOOLS_UNAVAILABLE" and not fixture.model_requests
