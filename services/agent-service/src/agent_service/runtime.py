"""Java 持有业务历史及运行权；Python 仅执行图、选择上下文并输出真实模型增量。"""

import asyncio
import logging
import time
from dataclasses import replace

import httpx
from langchain_core.messages import AIMessage, AIMessageChunk, HumanMessage
from langsmith import tracing_context

from agent_service.config import Settings
from agent_service.context.history import select_history
from agent_service.context.summary import background, policy, prepare_summary
from agent_service.contracts import AgentError, ChatRequest
from agent_service.conversations import ConversationClient
from agent_service.graph import PROMPT_VERSION, AgentContext
from agent_service.knowledge.adapter import TrustedContext, encode
from agent_service.knowledge.delegation import DelegationClient
from agent_service.timeline import StreamEvents, Timeline

logger = logging.getLogger(__name__)


class TurnWriter:
    def __init__(self, store, identity, run, context, settings, prompt_hash, emit):
        self.store, self.identity, self.run, self.context = (
            store,
            identity,
            run,
            context,
        )
        self.settings, self.prompt_hash, self.emit = settings, prompt_hash, emit
        self.timeline = Timeline(context)
        self.sequence = 0
        self.citations = []
        self.lock = asyncio.Lock()
        self.last_write = 0
        self.dirty = False
        self.status = "RUNNING"
        self.mutations = asyncio.Lock()

    @property
    def text(self):
        return self.timeline.text

    async def event(self, event, data):
        if self.emit:
            await self.emit(event, data)

    async def model_text(self, text, message_id):
        async with self.mutations:
            block, suffix = self.timeline.append_model(text, message_id)
            if not suffix:
                return
            self.dirty = True
            await self.event(
                "delta",
                {
                    "messageId": self.run["assistantMessageId"],
                    "blockId": block["blockId"],
                    "text": suffix,
                },
            )
            if time.monotonic() - self.last_write >= 0.4:
                await self.save()

    async def model_completed(self, message):
        if not isinstance(message, AIMessage):
            return
        await self.model_text(
            message.text, message.id or f"model-{self.context.model_calls}"
        )
        async with self.mutations:
            for block in self.timeline.prepare(message.tool_calls):
                self.dirty = True
                await self.save()
                await self.emit(
                    "tool",
                    self.context.tools[block["toolCallId"]],
                    block=self.timeline.visible(block),
                )

    async def changed(self, event, data):
        async with self.mutations:
            block = self.timeline.tool(data)
            self.dirty = True
            await self.save()
            await self.emit(event, data, block=self.timeline.visible(block))

    async def save(self, status="RUNNING", code=None):
        async with self.lock:
            self.sequence += 1
            response = await self.store.save(
                self.identity,
                self.run["runId"],
                {
                    "sequence": self.sequence,
                    "status": status,
                    "content": self.text,
                    "evidence": list(self.context.snapshots.values()),
                    "citations": self.citations,
                    "tools": list(self.context.tools.values()),
                    "blocks": self.timeline.blocks,
                    "eventSeq": self.emit.seq,
                    "model": self.settings.model,
                    "promptVersion": PROMPT_VERSION,
                    "promptHash": self.prompt_hash,
                    "errorCode": code,
                },
            )
            self.status = response["status"]
            self.dirty = False
            self.last_write = time.monotonic()
            if status == "RUNNING" and self.status != "RUNNING":
                raise AgentError(409, "AGENT_RUN_ENDED", "本轮生成已取消或结束")
            return response

    async def monitor(self):
        while True:
            await asyncio.sleep(0.5)
            if self.dirty:
                await self.save()
            state = await self.store.state(self.identity, self.run["runId"])
            self.status = state["run"]["status"]
            if self.status != "RUNNING":
                raise AgentError(409, "AGENT_RUN_ENDED", "本轮生成已取消或结束")


class AgentRuntime:
    def __init__(
        self, settings: Settings, gateway, graph, prompt_hash, summary_model=None
    ):
        self.settings, self.gateway, self.graph = settings, gateway, graph
        self.delegations = DelegationClient(gateway.client, settings.service_id)
        self.store = ConversationClient(gateway)
        self.prompt_hash = prompt_hash
        self.active = 0
        self.summary_model = summary_model

    async def run(
        self,
        request: ChatRequest,
        auth: httpx.BasicAuth,
        trace_id: str,
        emit=None,
        protocol_version=1,
    ):
        if self.active >= self.settings.concurrency:
            raise AgentError(503, "AGENT_CAPACITY", "Agent 当前繁忙，请稍后重试")
        self.active += 1
        try:
            deadline = time.monotonic() + self.settings.turn_timeout
            async with asyncio.timeout_at(deadline):
                return await self._turn(
                    request,
                    auth,
                    trace_id,
                    StreamEvents(emit, protocol_version),
                    deadline,
                )
        except TimeoutError as exc:
            raise AgentError(
                504, "AGENT_TIMEOUT", "本轮处理超时，已保存的内容可从历史查看"
            ) from exc
        finally:
            self.active -= 1

    async def _turn(self, request, auth, trace_id, emit, deadline):
        conversation_id = request.conversationId or request.sessionId
        if (
            request.conversationId
            and request.sessionId
            and request.conversationId != request.sessionId
        ):
            raise AgentError(400, "CONVERSATION_INVALID", "会话标识不一致")
        if conversation_id:
            if request.kbIds is not None or request.documentIds is not None:
                raise AgentError(400, "SESSION_SCOPE_FIXED", "已有会话不能修改知识范围")
        else:
            conversation_id = (await self.store.create(request, auth))["id"]
        async with self.delegations.issue(auth, str(conversation_id)) as grant:
            identity = TrustedContext.from_grant(
                grant,
                seconds=max(0.001, deadline - time.monotonic()),
                trace_id=trace_id,
            )
            identity = replace(identity, deadline=deadline)
            run = await self.store.begin(identity, request)
            previous = None
            if not run["created"]:
                previous = await self.store.state(identity, run["runId"])
            emit.bind(run, (previous or {}).get("message", {}).get("eventSeq", 0))
            if emit:
                await emit("meta", {**run, "persisted": True})
            if not run["created"]:
                state = previous
                message = state["message"]
                reply = self._reply(
                    identity,
                    state["run"],
                    message["content"],
                    message["citations"],
                    [
                        {
                            k: v
                            for k, v in t.items()
                            if k
                            in {
                                "status",
                                "requestId",
                                "traceId",
                                "degraded",
                                "strategy",
                                "toolTruncated",
                                "errorCode",
                                "retryable",
                            }
                        }
                        for t in message.get("tools", [])
                        if t["status"] != "RUNNING"
                    ],
                    True,
                    message,
                )
                if emit:
                    await emit("done", reply)
                return reply
            context = AgentContext(self.gateway, identity, self.settings)
            writer = TurnWriter(
                self.store,
                identity,
                run,
                context,
                self.settings,
                self.prompt_hash,
                emit,
            )
            context.changed = writer.changed
            context.model_completed = writer.model_completed
            generation = monitor = None
            try:

                async def execute():
                    messages = await self._messages(request, run, context, writer)
                    await self._generate(messages, run, context, writer)

                generation = asyncio.create_task(execute())
                monitor = asyncio.create_task(writer.monitor())
                await asyncio.wait(
                    {generation, monitor}, return_when=asyncio.FIRST_COMPLETED
                )
                if monitor.done():
                    await monitor
                await generation
                monitor.cancel()
                await asyncio.gather(monitor, return_exceptions=True)
                selected = context.references.validate_answer(writer.text)
                writer.citations = [r["evidenceId"] for r in selected]
                writer.timeline.settle("COMPLETED")
                terminal = await writer.save("COMPLETED")
                saved_view = (await self.store.state(identity, run["runId"]))["message"]
                reply = self._reply(
                    identity,
                    terminal,
                    saved_view["content"],
                    saved_view["citations"],
                    context.tool_events,
                    metadata=saved_view,
                )
                await writer.event("done", reply)
                return reply
            except BaseException as exc:
                for task in (generation, monitor):
                    if task:
                        task.cancel()
                await asyncio.gather(
                    *(t for t in (generation, monitor) if t), return_exceptions=True
                )
                status = (
                    "CANCELLED" if isinstance(exc, asyncio.CancelledError) else "FAILED"
                )
                if time.monotonic() >= identity.deadline or isinstance(
                    exc, TimeoutError
                ):
                    status = "TIMED_OUT"
                code = (
                    exc.code
                    if isinstance(exc, AgentError)
                    else "AGENT_TIMEOUT"
                    if status == "TIMED_OUT"
                    else "CLIENT_DISCONNECTED"
                    if status == "CANCELLED"
                    else "MODEL_UNAVAILABLE"
                )
                if isinstance(exc, ValueError):
                    code = "CITATION_INVALID"
                for tool in context.tools.values():
                    if tool["status"] == "RUNNING":
                        tool.update(status="UNAVAILABLE", errorCode=code)
                writer.timeline.settle(status, code)
                persisted = False
                saved_view = None
                try:
                    async with asyncio.timeout(3.5):
                        terminal = await asyncio.shield(writer.save(status, code))
                    status, persisted = terminal["status"], True
                    saved_view = (await self.store.state(identity, run["runId"]))[
                        "message"
                    ]
                except Exception:
                    logger.warning(
                        "agent settlement unavailable run_id=%s trace_id=%s",
                        run["runId"],
                        trace_id,
                    )
                if not isinstance(exc, asyncio.CancelledError):
                    try:
                        await writer.event(
                            "error", {"code": code, "persisted": persisted}
                        )
                        await writer.event(
                            "done",
                            {
                                **run,
                                "status": status,
                                "persisted": persisted,
                                "eventSeq": emit.seq,
                                **(
                                    {
                                        "blocks": saved_view["blocks"],
                                        "sourcesRestricted": saved_view.get(
                                            "sourcesRestricted", False
                                        ),
                                        "answer": saved_view["content"],
                                        "citations": saved_view["citations"],
                                        "retrievals": context.tool_events,
                                        "model": self.settings.model,
                                        "promptVersion": PROMPT_VERSION,
                                        "promptHash": self.prompt_hash,
                                    }
                                    if saved_view is not None
                                    else {}
                                ),
                                **(
                                    {"blocks": [], "sourcesRestricted": True}
                                    if code == "EVIDENCE_CHANGED"
                                    else {}
                                ),
                            },
                        )
                    except TimeoutError:
                        pass  # 慢客户端不占用已完成的执行权；客户端通过 Java 查询终态。
                if isinstance(exc, (asyncio.CancelledError, TimeoutError, AgentError)):
                    raise
                raise AgentError(
                    502 if code == "CITATION_INVALID" else 503,
                    code,
                    "本轮未完成，已保存的内容可从历史查看",
                ) from None
            finally:
                for task in (generation, monitor):
                    if task:
                        task.cancel()
                await asyncio.gather(
                    *(t for t in (generation, monitor) if t), return_exceptions=True
                )
                try:
                    await self.graph.checkpointer.adelete_thread(run["runId"])
                except Exception:
                    logger.warning(
                        "agent checkpoint cleanup unavailable run_id=%s", run["runId"]
                    )

    async def _messages(self, request, run, context, writer):
        identity = context.identity
        summary = await prepare_summary(
            self.store, identity, run, context, self.summary_model
        )
        history = await self.store.history_turns(
            identity, run["runId"], self.settings.history_turns
        )
        selection = select_history(
            history,
            run["runId"],
            min(24000, self.settings.context_byte_budget // 2),
            self.settings.history_turns,
        )
        # 只有近期完整窗口都被保留时才绑定已追齐的摘要，避免水位间出现空洞。
        if summary and len(selection.messages) != sum(
            1 + bool(t.get("assistant")) for t in history
        ):
            summary = None
        messages = selection.messages
        snapshots = [s for row in selection.assistants for s in row.get("evidence", [])]
        if summary:
            snapshots += summary["evidence"]
        for snapshot in snapshots:
            context.snapshots[snapshot["evidenceId"]] = {
                key: snapshot[key]
                for key in (
                    "evidenceId",
                    "kbId",
                    "documentId",
                    "chunkId",
                    "generation",
                    "content",
                    "sourceContentHash",
                    "snapshotHash",
                    "sourceMetadata",
                    "truncated",
                    "toolTruncated",
                    "versions",
                )
            }
        if (
            len(context.snapshots) > 64
            or len(encode(list(context.snapshots.values()))) > 131072
        ):
            raise AgentError(
                413, "CONTEXT_BUDGET", "历史与摘要的证据依赖过多，请缩小问题范围"
            )
        if summary:
            await self.store.use_summary(
                identity,
                {
                    "runId": run["runId"],
                    "revision": summary["revision"],
                    "retainedTurns": self.settings.history_turns,
                    "policyVersion": policy(context),
                },
            )
            messages.insert(0, background(summary))

        async def authorize():
            if summary:
                current = await self.store.summary(
                    identity, run["runId"], self.settings.history_turns, policy(context)
                )
                live_summary = current.get("summary")
                if (
                    not current["caughtUp"]
                    or not live_summary
                    or live_summary["revision"] != summary["revision"]
                    or live_summary["sourceHash"] != summary["sourceHash"]
                ):
                    raise AgentError(
                        409, "EVIDENCE_CHANGED", "摘要来源已变化，请重新提问"
                    )
            if selection.rows:
                turns = await self.store.history_turns(
                    identity, run["runId"], self.settings.history_turns
                )
                live = {
                    r["id"]: r["content"]
                    for turn in turns
                    for r in [turn["user"], turn.get("assistant")]
                    if r
                }
                if any(live.get(r["id"]) != r["content"] for r in selection.rows):
                    raise AgentError(
                        409, "EVIDENCE_CHANGED", "历史知识已变化，请重新提问"
                    )
            await writer.save()

        context.authorize_model = authorize
        messages.append(HumanMessage(content=request.question))
        return messages

    async def _generate(self, messages, run, context, writer):
        config = {
            "configurable": {"thread_id": run["runId"]},
            "recursion_limit": self.settings.max_model_calls * 4 + 8,
        }
        last = None
        streamed = {}
        with tracing_context(enabled=False):
            async for mode, data in self.graph.astream(
                {"messages": messages},
                config=config,
                context=context,
                stream_mode=["messages", "updates"],
            ):
                if mode == "messages":
                    message, metadata = data
                    if (
                        isinstance(message, AIMessageChunk)
                        and metadata.get("langgraph_node") == "model"
                        and metadata.get("lc_source") != "summarization"
                        and "context-summary" not in metadata.get("tags", [])
                    ):
                        text = message.text
                        if text:
                            message_id = message.id or f"model-{context.model_calls}"
                            streamed[message_id] = streamed.get(message_id, "") + text
                            await writer.model_text(streamed[message_id], message_id)
                elif mode == "updates" and isinstance(data, dict):
                    for update in data.values():
                        if isinstance(update, dict) and update.get("messages"):
                            candidate = update["messages"][-1]
                            if isinstance(candidate, AIMessage):
                                last = candidate
        if (
            last is None
            or last.tool_calls
            or not last.text.strip()
            or last.response_metadata.get("finish_reason") != "stop"
        ):
            raise AgentError(502, "AGENT_INCOMPLETE", "本轮未取得完整回答")
        if not writer.text:
            await writer.model_text(
                last.text, last.id or f"model-{context.model_calls}"
            )

    def _reply(
        self, identity, run, text, citations, retrievals, replayed=False, metadata=None
    ):
        metadata = metadata or {}
        keys = {
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
        }
        return {
            "sessionId": identity.agent_session_id,
            "conversationId": identity.agent_session_id,
            "runId": run["runId"],
            "userMessageId": run["userMessageId"],
            "assistantMessageId": run["assistantMessageId"],
            "traceId": run["traceId"],
            "status": run["status"],
            "answer": text,
            "citations": [{k: v for k, v in r.items() if k in keys} for r in citations],
            "retrievals": retrievals,
            "model": metadata.get("model") or self.settings.model,
            "promptVersion": metadata.get("promptVersion") or PROMPT_VERSION,
            "promptHash": metadata.get("promptHash") or self.prompt_hash,
            "replayed": replayed,
            "persisted": True,
            "blocks": metadata.get("blocks", []),
            "eventSeq": metadata.get("eventSeq", 0),
            "sourcesRestricted": metadata.get("sourcesRestricted", False),
        }
