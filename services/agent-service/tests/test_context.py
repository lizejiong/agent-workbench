import json
from dataclasses import replace
from types import SimpleNamespace

import pytest
from langchain_core.messages import AIMessage, HumanMessage, SystemMessage, ToolMessage

from agent_service.config import Settings
from agent_service.context.budget import RequestBudget
from agent_service.context.history import select_history
from agent_service.context.projection import prepare_request
from agent_service.contracts import AgentError


def settings(**values):
    return Settings(
        java_origin="http://127.0.0.1:8080",
        service_id="fixture",
        service_token="s" * 32,
        model_base_url="https://model.example/v1",
        model="fixture",
        model_api_key="fixture",
        **values,
    )


def turn(index, question="先不要提交，编号A-42", answer="只做说明，不执行提交"):
    return {
        "runId": f"run-{index}",
        "status": "COMPLETED",
        "user": {
            "id": f"u-{index}",
            "role": "user",
            "runId": f"run-{index}",
            "content": question,
            "evidence": [],
        },
        "assistant": {
            "id": f"a-{index}",
            "role": "assistant",
            "runId": f"run-{index}",
            "content": answer,
            "evidence": [],
        },
    }


def request(messages, tools=None):
    return SimpleNamespace(
        messages=messages,
        system_message=SystemMessage("系统规则"),
        tools=tools or [],
        tool_choice=None,
        response_format=None,
    )


def cycle(index, content):
    calls = [
        {
            "id": f"call-{index}-{i}",
            "name": "search_knowledge",
            "args": {"query": "独立查询问题"},
        }
        for i in range(2)
    ]
    model = AIMessage(content="", tool_calls=calls)
    results = [
        ToolMessage(
            content=json.dumps(
                {
                    "status": "READY",
                    "evidence": [
                        {
                            "evidenceId": f"K{index}_{i}",
                            "documentName": "资料",
                            "content": content,
                        }
                    ],
                },
                ensure_ascii=False,
            ),
            id=f"tool-{index}-{i}",
            tool_call_id=call["id"],
            name="search_knowledge",
        )
        for i, call in enumerate(calls)
    ]
    return [model, *reversed(results)]


def test_history_keeps_complete_recent_turns_and_fails_instead_of_splitting_latest():
    rows = [turn(1), turn(2, answer="很长的答复" * 1000), turn(3)]
    selected = select_history(rows, "current", 1200)
    assert [m.id for m in selected.messages] == ["u-3", "a-3"]
    assert (
        "先不要提交" in selected.messages[0].content
        and "A-42" in selected.messages[0].content
    )
    with pytest.raises(AgentError) as error:
        select_history(rows[:2], "current", 1200)
    assert error.value.code == "CONTEXT_BUDGET"


def test_unavailable_history_cannot_become_a_completed_operation_or_new_citation():
    failed = turn(1)
    failed.update(status="CANCELLED", assistant=None)
    valid = turn(2, answer="之前的说明[K" + "a" * 32 + "_E1]")
    selected = select_history([failed, valid], "current", 4000)
    assert (
        selected.messages[0].type == "human"
        and "不代表操作已完成" in selected.messages[0].content
    )
    assert "[K" not in selected.messages[-1].content
    assert len(selected.assistants) == 1


def test_complete_request_budget_includes_system_and_tool_schema_and_output_reserve():
    budget = RequestBudget(
        settings(model_context_tokens=4096, context_safety_tokens=512)
    )
    req = request([HumanMessage("问题")])
    baseline = budget.measure(req)
    req.tools = [
        {
            "type": "function",
            "function": {
                "name": "large",
                "description": "参数说明" * 1200,
                "parameters": {"type": "object"},
            },
        }
    ]
    assert budget.measure(req).bytes > baseline.bytes
    with pytest.raises(AgentError) as error:
        prepare_request(req, budget)
    assert error.value.code == "CONTEXT_BUDGET"
    assert budget.input_limit == 4096 - 1024 - 512


def test_projection_keeps_parallel_pairing_recent_batches_and_original_messages():
    messages = [
        HumanMessage("当前限制：不要提交。"),
        *cycle(1, "历史正文" * 1300),
        *cycle(2, "最近证据"),
        *cycle(3, "当前证据"),
    ]
    req = request(messages)
    budget = RequestBudget(
        settings(model_context_tokens=8192, context_safety_tokens=1024)
    )
    original = [m.model_dump() for m in messages]
    projected, size, count = prepare_request(req, budget)
    assert count == 1 and budget.fits(size)
    assert [m.model_dump() for m in messages] == original
    assert projected[0].content == "当前限制：不要提交。"
    assert projected[1].tool_calls == messages[1].tool_calls
    for index in (2, 3):
        data = json.loads(projected[index].content)
        assert data["status"] == "READY" and data["contextOmitted"]
        assert (
            data["evidenceRefs"]
            and projected[index].tool_call_id == messages[index].tool_call_id
        )
        assert projected[index].id == messages[index].id
    assert all(projected[i] is messages[i] for i in range(4, len(messages)))


def test_unsettled_calls_never_reach_the_model_and_current_question_is_not_truncated():
    batch = cycle(1, "证据")
    budget = RequestBudget(settings())
    with pytest.raises(AgentError) as error:
        prepare_request(request([HumanMessage("问题"), batch[0], batch[1]]), budget)
    assert error.value.code == "CONTEXT_PROTOCOL"
    question = HumanMessage("用户当前问题" * 7000)
    with pytest.raises(AgentError):
        prepare_request(request([question]), budget)
    assert len(question.content) == len("用户当前问题" * 7000)


def test_usage_calibration_is_local_and_unknown_tokenizer_configuration_is_rejected():
    config = settings()
    one, other = RequestBudget(config), RequestBudget(config)
    req = request([HumanMessage("中文问题")])
    size = one.measure(req)
    one.observe(
        size,
        SimpleNamespace(
            result=[
                AIMessage(
                    "答复",
                    usage_metadata={
                        "input_tokens": size.tokens * 2,
                        "output_tokens": 1,
                        "total_tokens": size.tokens * 2 + 1,
                    },
                )
            ]
        ),
    )
    assert one.measure(req).tokens > size.tokens
    assert other.measure(req).tokens == size.tokens
    with pytest.raises(ValueError):
        replace(config, token_counter="tiktoken")
