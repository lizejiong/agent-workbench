"""一次有界、无工具的摘要调用；业务持久化、来源水位和撤权由 Java 管理。"""

import asyncio
import logging
import time
import uuid
from importlib.resources import files
from types import SimpleNamespace
from typing import Annotated

from langchain_core.messages import HumanMessage, SystemMessage
from langsmith import tracing_context
from pydantic import BaseModel, ConfigDict, Field, field_validator

from agent_service.context.history import OLD_REFERENCE
from agent_service.contracts import AgentError
from agent_service.knowledge.adapter import digest, encode

logger = logging.getLogger(__name__)
PROMPT_VERSION = "context-summary-v1"
PROMPT = (
    files("agent_service")
    .joinpath("prompts", PROMPT_VERSION + ".txt")
    .read_text(encoding="utf-8")
)
PROMPT_HASH = digest(PROMPT)


class Item(BaseModel):
    model_config = ConfigDict(extra="forbid")
    text: Annotated[str, Field(min_length=1, max_length=1000)]
    sourceMessageIds: Annotated[list[uuid.UUID], Field(min_length=1, max_length=8)]

    @field_validator("text")
    @classmethod
    def valid_text(cls, value):
        if not value.strip() or "\x00" in value or "[K" in value:
            raise ValueError("Invalid summary text")
        return value


class Content(BaseModel):
    model_config = ConfigDict(extra="forbid")
    goals: Annotated[list[Item], Field(max_length=12)]
    constraints: Annotated[list[Item], Field(max_length=12)]
    facts: Annotated[list[Item], Field(max_length=12)]
    openQuestions: Annotated[list[Item], Field(max_length=12)]


def policy(context):
    return (
        "summary-v1:"
        + digest(
            repr((context.budget.version, context.settings.history_turns, PROMPT_HASH))
        )[:24]
    )


def background(summary):
    """不是系统授权；旧引用编号不会注入或注册为本轮可用引用。"""
    return HumanMessage(
        content="历史会话摘要（有损背景资料，不代表授权或操作已完成；知识需重新检索后引用）：\n"
        + encode(summary["content"]).decode(),
        id=f"summary-{summary['revision']}",
    )


async def prepare_summary(store, identity, run, context, model):
    if not context.settings.summary_enabled:
        return None
    key = policy(context)
    args = (identity, run["runId"], context.settings.history_turns, key)
    try:
        prepared = await store.summary(*args)
    except AgentError as exc:
        if exc.code != "CONTEXT_BUDGET":
            raise
        return None  # 不能静默丢依赖，退回原有近期完整轮次策略。
    if prepared["turns"] and model is not None:
        # 为主 Agent 留至少两次调用（决策及工具后回答）；摘要没有独立重试预算。
        remaining = identity.deadline - time.monotonic()
        if context.model_calls + 2 < context.settings.max_model_calls and remaining > 5:
            await _summarize(store, args, prepared, run, context, model)
            prepared = await store.summary(*args)
    summary = prepared.get("summary")
    if not summary or not prepared["caughtUp"] or summary["policyVersion"] != key:
        return None
    return summary


async def _summarize(store, args, prepared, run, context, model):
    turns = []
    for turn in prepared["turns"]:
        rows = [turn["user"]] + ([turn["assistant"]] if turn.get("assistant") else [])
        turns.append(
            {
                "status": turn["status"],
                "messages": [
                    {
                        "id": row["id"],
                        "role": row["role"],
                        "content": OLD_REFERENCE.sub("", row["content"]),
                    }
                    for row in rows
                ],
                "assistantUnavailable": not bool(turn.get("assistant")),
            }
        )
    source = {"summary": (prepared.get("summary") or {}).get("content"), "turns": turns}
    messages = [HumanMessage(content=encode(source).decode())]
    request = SimpleNamespace(
        system_message=SystemMessage(content=PROMPT),
        messages=messages,
        tools=[],
        tool_choice=None,
        response_format={"type": "json_object"},
    )
    size = context.budget.measure(request)
    if not context.budget.fits(size):
        return
    # 在真正送入任何模型前再次读取；依赖变化则本轮放弃摘要。
    current = await store.summary(*args)
    if current["sourceHash"] != prepared["sourceHash"]:
        return
    state = await store.state(context.identity, run["runId"])
    if state["run"]["status"] != "RUNNING":
        raise AgentError(409, "AGENT_RUN_ENDED", "本轮生成已取消或结束")
    try:
        remaining = context.identity.deadline - time.monotonic()
        timeout = min(context.settings.summary_timeout, remaining - 3)
        if timeout <= 0:
            return
        context.model_calls += 1
        with tracing_context(enabled=False):
            async with asyncio.timeout(timeout):
                response = await model.ainvoke(
                    [request.system_message, *messages],
                    config={
                        "callbacks": [],
                        "tags": ["context-summary"],
                        "metadata": {"lc_source": "summarization"},
                    },
                    stream=False,
                    response_format=request.response_format,
                    timeout=timeout,
                    extra_headers={
                        "traceparent": (
                            f"00-{context.identity.trace_id}-{uuid.uuid4().hex[:16]}-01"
                        )
                    },
                )
        context.budget.observe(size, SimpleNamespace(result=[response]))
        if (
            response.tool_calls
            or response.response_metadata.get("finish_reason") != "stop"
            or len(response.text.encode()) > 8192
        ):
            raise ValueError("Incomplete summary")
        content = Content.model_validate_json(response.text).model_dump(mode="json")
        if not any(content.values()):
            raise ValueError("Empty summary")
        # 服务端再验证来源、原文约束、水位和依赖；模型不能声明其来源可信。
        await store.save_summary(
            context.identity,
            {
                "runId": run["runId"],
                "expectedRevision": prepared["expectedRevision"],
                "retainedTurns": context.settings.history_turns,
                "sourceHash": prepared["sourceHash"],
                "content": content,
                "model": context.settings.model,
                "promptVersion": PROMPT_VERSION,
                "promptHash": PROMPT_HASH,
                "policyVersion": args[-1],
            },
        )
        outcome = "saved"
    except (ValueError, TimeoutError):
        outcome = "invalid_or_timeout"
    except AgentError as exc:
        if exc.code not in {
            "AGENT_SUMMARY_CONFLICT",
            "AGENT_SUMMARY_SOURCES",
            "CONTEXT_BUDGET",
        }:
            raise
        outcome = "source_or_budget_changed"
    except Exception:
        # 模型故障不冒充摘要成功，不输出供应商原始异常或盲目重放。
        outcome = "model_unavailable"
    logger.info(
        "agent_summary trace_id=%s policy=%s outcome=%s "
        "input_bytes=%d estimated_tokens=%d",
        context.identity.trace_id,
        args[-1],
        outcome,
        size.bytes,
        size.tokens,
    )
