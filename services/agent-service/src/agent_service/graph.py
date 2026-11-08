"""使用官方 create_agent 创建 LangGraph 循环，不另写 ReAct。"""

import logging
import time
import uuid
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field
from importlib.resources import files

from langchain.agents import create_agent
from langchain.agents.middleware import AgentMiddleware
from langchain.agents.middleware.types import ModelRequest
from langchain.tools import ToolRuntime, tool
from langchain_core.messages import SystemMessage

from agent_service.config import Settings
from agent_service.context.budget import RequestBudget
from agent_service.context.projection import prepare_request
from agent_service.context.tool_results import POLICY_VERSION as TOOL_BUDGET_VERSION
from agent_service.context.tool_results import allocate_results
from agent_service.contracts import AgentError, KnowledgeQuery
from agent_service.knowledge.adapter import (
    KnowledgeGateway,
    TrustedContext,
    TurnReferences,
    digest,
    model_view,
)

logger = logging.getLogger(__name__)

PROMPT_FILES = (
    "agent-system-v2.txt",
    "agent-knowledge-v2.txt",
    "agent-preferences-v1.txt",
)
PROMPT_VERSION = "+".join(name.removesuffix(".txt") for name in PROMPT_FILES)
SYSTEM_PROMPT = "\n\n".join(
    files("agent_service").joinpath("prompts", name).read_text(encoding="utf-8")
    for name in PROMPT_FILES
)


@dataclass
class AgentContext:
    gateway: KnowledgeGateway
    identity: TrustedContext
    settings: Settings
    references: TurnReferences = field(default_factory=TurnReferences)
    tool_events: list[dict] = field(default_factory=list)
    public_sources: dict[str, dict] = field(default_factory=dict)
    model_calls: int = 0
    tool_calls: int = 0
    snapshots: dict[str, dict] = field(default_factory=dict)
    tools: dict[str, dict] = field(default_factory=dict)
    tool_evidence: dict[str, list[str]] = field(default_factory=dict)
    changed: Callable | None = None
    model_completed: Callable | None = None
    authorize_model: Callable[[], Awaitable] | None = None
    budget: RequestBudget = field(init=False)
    tool_allowances: dict = field(default_factory=dict)

    def __post_init__(self):
        self.budget = RequestBudget(self.settings)


@tool
async def search_knowledge(
    query: KnowledgeQuery, runtime: ToolRuntime[AgentContext]
) -> dict:
    """用完整独立问题检索当前授权知识，返回证据、来源和状态；不生成答案。"""
    context = runtime.context
    context.tool_calls += 1
    if context.tool_calls > context.settings.max_tool_calls:
        raise AgentError(429, "TOOL_CALL_BUDGET", "本轮工具调用预算已用尽")
    call_id = runtime.tool_call_id
    context.tools[call_id] = {
        "toolCallId": call_id,
        "name": "search_knowledge",
        "status": "RUNNING",
    }
    if context.changed:
        await context.changed("tool", context.tools[call_id])
    allowance = context.tool_allowances.get(call_id)
    if allowance is None:
        raise AgentError(502, "CONTEXT_PROTOCOL", "工具调用缺少当前批次预算")
    result = await context.gateway.bind(
        context.identity,
        context.references,
        byte_budget=allowance.byte_limit,
        accepts=allowance.accepts,
        result_view=model_view,
    )(query)
    context.tool_events.append(
        {
            key: result[key]
            for key in (
                "status",
                "requestId",
                "traceId",
                "degraded",
                "strategy",
                "toolTruncated",
                "errorCode",
                "retryable",
            )
            if key in result
        }
    )
    for evidence in result["evidence"]:
        if (
            len(context.snapshots) >= 128
            and evidence["evidenceId"] not in context.snapshots
        ):
            raise AgentError(413, "CONTEXT_BUDGET", "本轮证据依赖过多，请缩小问题范围")
        context.snapshots[evidence["evidenceId"]] = {
            key: evidence[key]
            for key in (
                "evidenceId",
                "kbId",
                "documentId",
                "chunkId",
                "generation",
                "content",
                "sourceContentHash",
                "sourceMetadata",
                "truncated",
                "toolTruncated",
            )
        }
        context.snapshots[evidence["evidenceId"]].update(
            {
                "snapshotHash": digest(evidence["content"]),
                "versions": context.references.sources[evidence["evidenceId"]].get(
                    "versions", {}
                ),
            }
        )
        context.public_sources[evidence["evidenceId"]] = {
            key: evidence[key]
            for key in (
                "evidenceId",
                "kbId",
                "documentId",
                "chunkId",
                "generation",
                "documentName",
                "sourceMetadata",
                "snapshotHash",
                "fileUrl",
                "truncated",
                "toolTruncated",
            )
        }
    context.tools[call_id] = {
        "toolCallId": call_id,
        "name": "search_knowledge",
        **context.tool_events[-1],
    }
    context.tool_evidence[call_id] = [e["evidenceId"] for e in result["evidence"]]
    if context.changed:
        await context.changed("tool", context.tools[call_id])
    return model_view(result)


class TurnLimits(AgentMiddleware):
    async def abefore_model(self, state, runtime):
        context = runtime.context
        context.model_calls += 1
        if context.model_calls > context.settings.max_model_calls:
            raise AgentError(429, "MODEL_CALL_BUDGET", "本轮模型调用预算已用尽")
        if time.monotonic() >= context.identity.deadline:
            raise TimeoutError
        if context.authorize_model:
            await context.authorize_model()

    async def awrap_model_call(self, request, handler):
        context = request.runtime.context
        remaining = context.identity.deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError
        messages, size, projected = prepare_request(request, context.budget)
        # 计数或首次加载 tokenizer 也消耗原 deadline，不能重新开始 HTTP 预算。
        remaining = context.identity.deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError
        logger.info(
            "agent_context trace_id=%s policy=%s input_bytes=%d "
            "estimated_tokens=%d counter=%s projected_batches=%d",
            context.identity.trace_id,
            context.budget.version,
            size.bytes,
            size.tokens,
            size.counter,
            projected,
        )
        response = await handler(
            request.override(
                messages=messages,
                model_settings={
                    **request.model_settings,
                    "timeout": min(40, remaining),
                    "extra_headers": {
                        "traceparent": (
                            f"00-{context.identity.trace_id}-{uuid.uuid4().hex[:16]}-01"
                        ),
                    },
                },
            )
        )
        context.budget.observe(size, response)
        context.tool_allowances = allocate_results(
            request.override(messages=messages),
            response,
            context.budget,
            context.settings.tool_byte_budget,
        )
        if context.tool_allowances:
            logger.info(
                "agent_tool_budget trace_id=%s policy=%s calls=%d "
                "max_result_bytes=%d max_result_estimated_tokens=%d",
                context.identity.trace_id,
                TOOL_BUDGET_VERSION,
                len(context.tool_allowances),
                min(a.byte_limit for a in context.tool_allowances.values()),
                min(a.tokens for a in context.tool_allowances.values()),
            )
        if context.model_completed:
            for message in response.result:
                await context.model_completed(message)
        return response


def planning_request(messages):
    """历史选择与实际图共享系统提示及工具 Schema，先计入固定输入和当前问题。"""
    return ModelRequest(
        model=None,
        system_message=SystemMessage(SYSTEM_PROMPT),
        messages=messages,
        tools=[search_knowledge],
    )


def build_graph(model, saver):
    graph = create_agent(
        model=model,
        tools=[search_knowledge],
        system_prompt=SYSTEM_PROMPT,
        middleware=[TurnLimits()],
        context_schema=AgentContext,
        checkpointer=saver,
        name="knowledge_agent",
    )
    return graph, digest(SYSTEM_PROMPT)
