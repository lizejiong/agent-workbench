"""每轮注入新身份、调用预算和引用表；图检查点不承载凭据。"""

import asyncio
import logging
import time
import uuid

import httpx
from langchain_core.messages import AIMessage, HumanMessage, RemoveMessage
from langgraph.graph.message import REMOVE_ALL_MESSAGES
from langsmith import tracing_context

from agent_service.config import Settings
from agent_service.contracts import AgentError, ChatRequest
from agent_service.graph import AgentContext
from agent_service.knowledge.adapter import TrustedContext
from agent_service.knowledge.delegation import DelegationClient
from agent_service.sessions import Sessions

logger = logging.getLogger(__name__)


class AgentRuntime:
    def __init__(
        self, settings: Settings, gateway, graph, sessions: Sessions, prompt_hash
    ):
        self.settings = settings
        self.gateway = gateway
        self.graph = graph
        self.sessions = sessions
        self.delegations = DelegationClient(gateway.client, settings.service_id)
        self.prompt_hash = prompt_hash
        self.active = 0

    async def run(self, request: ChatRequest, auth: httpx.BasicAuth, trace_id: str):
        # 检查与加计数之间没有 await；单个事件循环内拒绝排队。
        if self.active >= self.settings.concurrency:
            raise AgentError(503, "AGENT_CAPACITY", "Agent 当前繁忙，请稍后重试")
        self.active += 1
        try:
            async with asyncio.timeout(self.settings.turn_timeout):
                return await self._turn(
                    request,
                    auth,
                    trace_id,
                    time.monotonic() + self.settings.turn_timeout,
                )
        except TimeoutError as exc:
            raise AgentError(
                504, "AGENT_TIMEOUT", "本轮处理超时，请缩小问题范围"
            ) from exc
        finally:
            self.active -= 1

    async def _turn(
        self,
        request: ChatRequest,
        auth: httpx.BasicAuth,
        trace_id: str,
        deadline: float,
    ):
        session_id = str(request.sessionId or uuid.uuid4())
        run_id = str(uuid.uuid4())
        if request.sessionId is None:
            scope = {
                "kbIds": None
                if request.kbIds is None
                else list(map(str, request.kbIds)),
                "documentIds": None
                if request.documentIds is None
                else list(map(str, request.documentIds)),
            }
        else:
            if request.kbIds is not None or request.documentIds is not None:
                raise AgentError(400, "SESSION_SCOPE_FIXED", "已有会话不能修改知识范围")
            scope = await self.sessions.scope(session_id)
        async with self.delegations.issue(auth, session_id, scope) as grant:
            identity = TrustedContext.from_grant(
                grant,
                seconds=max(0.001, deadline - time.monotonic()),
                trace_id=trace_id,
            )
            if request.sessionId is None:
                await self.sessions.create(grant)
            await self.sessions.acquire(grant, run_id, self.settings.turn_timeout)
            try:
                return await self._invoke(request, run_id, identity)
            finally:
                await self.sessions.release(session_id, run_id)

    async def _invoke(
        self, request: ChatRequest, run_id: str, identity: TrustedContext
    ):
        config = {
            "configurable": {"thread_id": identity.agent_session_id},
            "recursion_limit": self.settings.max_model_calls * 4 + 8,
        }
        context = AgentContext(self.gateway, identity, self.settings)
        # 禁用环境中可能打开的第三方追踪，凭据/资料不通过回调导出。
        with tracing_context(enabled=False):
            snapshot = await self.graph.aget_state(config)
            # 权限变化后旧正文不能重用：仅保留至多 4 条、共 8000 字节的用户消息。
            history = []
            used = 0
            for message in reversed(snapshot.values.get("messages", [])):
                if not isinstance(message, HumanMessage):
                    continue
                size = len(str(message.content).encode("utf-8"))
                if len(history) >= 4 or used + size > 8000:
                    break
                history.append(message)
                used += size
            messages = [
                RemoveMessage(id=REMOVE_ALL_MESSAGES),
                *reversed(history),
                HumanMessage(content=request.question),
            ]
            try:
                result = await self.graph.ainvoke(
                    {"messages": messages}, config=config, context=context
                )
            except (AgentError, TimeoutError, asyncio.CancelledError):
                raise
            except Exception as exc:
                # 上游原始正文可能包含地址/密钥，不能进入响应或常规日志。
                logger.warning(
                    "agent invocation failed type=%s run_id=%s trace_id=%s",
                    type(exc).__name__,
                    run_id,
                    identity.trace_id,
                )
                raise AgentError(
                    503, "MODEL_UNAVAILABLE", "主 Agent 模型暂不可用"
                ) from None
        final = result["messages"][-1]
        if not isinstance(final, AIMessage) or final.tool_calls:
            raise AgentError(502, "AGENT_INCOMPLETE", "本轮未取得完整回答")
        answer = final.text
        if not answer.strip():
            raise AgentError(502, "AGENT_EMPTY_ANSWER", "本轮未取得有效回答")
        try:
            selected = context.references.validate_answer(answer)
        except ValueError as exc:
            raise AgentError(
                502, "CITATION_INVALID", "回答引用校验未通过，请重新提问"
            ) from exc
        citations = [context.public_sources[item["evidenceId"]] for item in selected]
        return {
            "sessionId": identity.agent_session_id,
            "runId": run_id,
            "traceId": identity.trace_id,
            "status": "COMPLETED",
            "answer": answer,
            "citations": citations,
            "retrievals": context.tool_events,
            "model": self.settings.model,
            "promptVersion": "agent-system-v1+agent-knowledge-v1",
            "promptHash": self.prompt_hash,
        }
