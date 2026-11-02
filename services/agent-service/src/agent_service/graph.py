"""使用官方 create_agent 创建 LangGraph 循环，不另写 ReAct。"""

import time
import uuid
from dataclasses import dataclass, field
from importlib.resources import files

from langchain.agents import create_agent
from langchain.agents.middleware import AgentMiddleware
from langchain.tools import ToolRuntime, tool
from langchain_core.messages import messages_to_dict

from agent_service.config import Settings
from agent_service.contracts import AgentError, KnowledgeQuery
from agent_service.knowledge.adapter import (
    KnowledgeGateway,
    TrustedContext,
    TurnReferences,
    digest,
    encode,
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


@tool
async def search_knowledge(
    query: KnowledgeQuery, runtime: ToolRuntime[AgentContext]
) -> dict:
    """用完整独立问题检索当前授权知识，返回证据、来源和状态；不生成答案。"""
    context = runtime.context
    context.tool_calls += 1
    if context.tool_calls > context.settings.max_tool_calls:
        raise AgentError(429, "TOOL_CALL_BUDGET", "本轮工具调用预算已用尽")
    result = await context.gateway.bind(context.identity, context.references)(query)
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
    return result


class TurnLimits(AgentMiddleware):
    def __init__(self, prompt_bytes: int):
        self.prompt_bytes = prompt_bytes

    async def abefore_model(self, state, runtime):
        context = runtime.context
        context.model_calls += 1
        if context.model_calls > context.settings.max_model_calls:
            raise AgentError(429, "MODEL_CALL_BUDGET", "本轮模型调用预算已用尽")
        if time.monotonic() >= context.identity.deadline:
            raise TimeoutError
        if (
            len(encode(messages_to_dict(state["messages"]))) + self.prompt_bytes + 2048
            > context.settings.context_byte_budget
        ):
            raise AgentError(413, "CONTEXT_BUDGET", "本轮上下文过长，请缩小问题范围")

    async def awrap_model_call(self, request, handler):
        context = request.runtime.context
        remaining = context.identity.deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError
        return await handler(
            request.override(
                model_settings={
                    **request.model_settings,
                    "timeout": min(40, remaining),
                    "extra_headers": {
                        "traceparent": (
                            f"00-{context.identity.trace_id}-{uuid.uuid4().hex[:16]}-01"
                        ),
                    },
                }
            )
        )


def build_graph(model, saver):
    prompt_dir = files("agent_service").joinpath("prompts")
    prompt = (
        prompt_dir.joinpath("agent-system-v1.txt").read_text(encoding="utf-8")
        + "\n\n"
        + prompt_dir.joinpath("agent-knowledge-v1.txt").read_text(encoding="utf-8")
    )
    graph = create_agent(
        model=model,
        tools=[search_knowledge],
        system_prompt=prompt,
        middleware=[TurnLimits(len(prompt.encode("utf-8")))],
        context_schema=AgentContext,
        checkpointer=saver,
        name="knowledge_agent",
    )
    return graph, digest(prompt)
