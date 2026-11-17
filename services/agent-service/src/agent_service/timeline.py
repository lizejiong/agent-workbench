"""稳定内容块与 SSE 投影；不暴露推理正文、原始工具异常或服务凭据。"""

import asyncio
import re
import time
import uuid
from copy import deepcopy
from datetime import UTC, datetime

from agent_service.contracts import AgentError


def block_id():
    return "B" + uuid.uuid4().hex


def safe_query(value):
    if not isinstance(value, str) or not value.strip():
        return None
    value = re.sub(r"(?i)(bearer\s+)\S+", r"\1[已隐藏]", value)
    value = re.sub(r"\bsk-[A-Za-z0-9_-]{8,}", "[已隐藏]", value)
    value = re.sub(
        r"(?i)((?:api[_-]?key|token|password|secret|authorization)\s*[:=]\s*)[^\s&;,]+",
        r"\1[已隐藏]",
        value,
    )
    value = re.sub(r"(https?://)[^/\s:@]+:[^/\s@]+@", r"\1[已隐藏]@", value)
    return {"query": value[:1000]}


class StreamEvents:
    def __init__(self, sink, version=1):
        self.sink, self.version = sink, version
        self.seq = 0
        self.run = {}
        self.ended = False
        self.lock = asyncio.Lock()

    def bind(self, run, seq=0):
        self.run = run
        self.seq = seq

    async def _send(self, event, data):
        if self.ended:
            return
        self.seq += 1
        if self.sink:
            envelope = {
                "protocolVersion": 2,
                "seq": self.seq,
                **{
                    key: self.run[key]
                    for key in ("runId", "conversationId", "requestId", "traceId")
                    if key in self.run
                },
                "messageId": self.run.get("assistantMessageId"),
            }
            envelope["protocolVersion"] = self.version
            from agent_service.knowledge.adapter import encode

            payload = {**data, **envelope} if self.version >= 2 else data
            if len(encode(payload)) >= 63000:
                if event in {"finish", "cancel", "done"}:
                    payload = {
                        **{
                            k: v
                            for k, v in payload.items()
                            if k
                            in {
                                "status",
                                "persisted",
                                "replayed",
                                "sourcesRestricted",
                                "runId",
                                "conversationId",
                                "messageId",
                                "assistantMessageId",
                                "seq",
                                "traceId",
                                "protocolVersion",
                            }
                        },
                        "snapshotRequired": True,
                    }
                else:
                    raise AgentError(
                        413, "STREAM_EVENT_BUDGET", "事件过大，请读取已保存历史"
                    )
            await self.sink(event, payload)
        if event == "done":
            self.ended = True

    async def __call__(self, event, data, *, block=None):
        async with self.lock:
            await self.dispatch(event, data, block=block)

    async def dispatch(self, event, data, *, block=None):
        if self.version == 1:
            # 旧客户端保持 meta/delta/tool/error/done，不发送新事件的重复副本。
            if event not in {"block", "finish", "cancel", "hint"}:
                await self._send(event, data)
            return
        if event == "delta":
            for offset in range(0, len(data["text"]), 8000):
                await self._send(
                    "message",
                    {
                        "type": "answer",
                        "blockId": data["blockId"],
                        "delta": data["text"][offset : offset + 8000],
                    },
                )
        elif event == "tool":
            if self.version == 3:
                from agent_service.tools.events import ToolEvent

                status = block.get("toolStatus", "running")
                status = {
                    "RUNNING": block["status"],
                    "READY": "success",
                    "NO_EVIDENCE": "empty",
                    "NEEDS_CLARIFICATION": "empty",
                    "UNAVAILABLE": "unavailable",
                }.get(status, status)
                if block["status"] == "pending":
                    status = "pending"
                phase = (
                    "start"
                    if status in {"pending", "running"}
                    else "success"
                    if status in {"success", "empty"}
                    else "cancel"
                    if status == "cancelled"
                    else "failure"
                )
                projection = ToolEvent(
                    eventType=phase,
                    callId=block["toolCallId"],
                    toolKey=block.get("toolKey", block["name"]),
                    displayName=block["displayName"],
                    batchId=block["batchId"],
                    order=block["callIndex"],
                    status=status,
                    startedAt=block.get("startedAt"),
                    durationMs=block.get("durationMs"),
                    summary=block.get("summary", ""),
                    block=block,
                )
                await self._send("tool." + phase, projection.model_dump(mode="json"))
            else:
                await self._send("block", block)
        elif event == "done":
            if data.get("status") != "RUNNING":
                await self._send(
                    "cancel" if data.get("status") == "CANCELLED" else "finish", data
                )
            await self._send("done", data)
        else:
            await self._send(event, data)


class Timeline:
    def __init__(self, context):
        self.context = context
        self.blocks = []
        self.models = {}
        self.clock = {}

    @property
    def text(self):
        return "\n\n".join(b["text"] for b in self.blocks if b["kind"] == "answer")

    def append_model(self, text, message_id):
        previous = self.models.get(message_id, "")
        if previous.startswith(text):
            return None, ""  # 完成回调可能先于已排队的 token 被消费。
        if not text.startswith(previous):
            raise AgentError(502, "AGENT_TEXT_CONFLICT", "模型文本增量不一致")
        suffix = text[len(previous) :]
        self.models[message_id] = text
        if not suffix:
            return None, ""
        if not self.blocks or self.blocks[-1]["kind"] != "answer":
            self.blocks.append(
                {
                    "blockId": block_id(),
                    "kind": "answer",
                    "status": "streaming",
                    "text": "",
                }
            )
        block = self.blocks[-1]
        block["text"] += suffix
        self.check_budget()
        return block, suffix

    def prepare(self, calls):
        batch = block_id()
        created = []
        for index, call in enumerate(calls):
            if self.find(call["id"]):
                continue
            for item in self.blocks:
                if item["kind"] == "answer" and item["status"] == "streaming":
                    item["status"] = "done"
            item = {
                "blockId": block_id(),
                "kind": "tool",
                "toolCallId": call["id"],
                "name": call["name"],
                "status": "pending",
                "batchId": batch,
                "callIndex": index,
                "arguments": self.arguments(call.get("args", {})),
                "evidenceIds": [],
            }
            self.context.tools.setdefault(
                call["id"],
                {
                    "toolCallId": call["id"],
                    "name": call["name"],
                    "status": "RUNNING",
                },
            )
            self.blocks.append(item)
            created.append(item)
        self.check_budget()
        return created

    def arguments(self, value):
        from agent_service.tools.executor import bounded

        return bounded(value, 2048)[0] if isinstance(value, dict) else None

    def find(self, call_id):
        return next((b for b in self.blocks if b.get("toolCallId") == call_id), None)

    def tool(self, data):
        item = self.find(data["toolCallId"])
        if item is None:
            item = self.prepare(
                [{"id": data["toolCallId"], "name": data["name"], "args": {}}]
            )[0]
        if data["status"] == "RUNNING":
            if item["status"] == "running":
                return item
            item["status"] = "running"
            item["startedAt"] = datetime.now(UTC).isoformat()
            self.clock[data["toolCallId"]] = time.monotonic()
        else:
            item["status"] = (
                "interrupted"
                if data["status"] == "cancelled"
                else "failed"
                if data["status"]
                in {
                    "UNAVAILABLE",
                    "failed",
                    "timeout",
                    "denied",
                    "invalid_arguments",
                    "unavailable",
                }
                else "done"
            )
            item["durationMs"] = self.duration(item)
            item["evidenceIds"] = self.context.tool_evidence.get(data["toolCallId"], [])
            if data.get("errorCode"):
                item["errorCode"] = data["errorCode"]
        return item

    def duration(self, item):
        return min(
            120000,
            max(
                0,
                int(
                    (
                        time.monotonic()
                        - self.clock.get(item["toolCallId"], time.monotonic())
                    )
                    * 1000
                ),
            ),
        )

    def settle(self, status, code=None):
        changed = []
        for item in self.blocks:
            if item["kind"] == "answer" and item["status"] == "streaming":
                item["status"] = "done" if status == "COMPLETED" else "interrupted"
            elif item["kind"] == "tool" and item["status"] in {"pending", "running"}:
                item.update(
                    status="interrupted",
                    durationMs=self.duration(item),
                    errorCode=code or "AGENT_RUN_ENDED",
                )
                item.setdefault("startedAt", datetime.now(UTC).isoformat())
                changed.append(item)
        if status in {"FAILED", "TIMED_OUT"}:
            self.blocks.append(
                {
                    "blockId": block_id(),
                    "kind": "error",
                    "status": "failed",
                    "text": "本轮未完成。请查看已保存内容及运行状态后再决定是否重试。",
                    "errorCode": code,
                }
            )
        self.check_budget()
        return changed

    def check_budget(self):
        if len(self.blocks) > 128 or len(self.text) > 65536:
            raise AgentError(413, "CONTEXT_BUDGET", "本轮内容块或输出过长")

    def visible(self, item):
        result = deepcopy(item)
        if item["kind"] == "tool":
            registry = self.context.registry
            definition = registry.definitions.get(item["name"]) if registry else None
            result["displayName"] = (
                definition.displayName if definition else "知识库检索"
            )
            tool = self.context.tools.get(item["toolCallId"], {})
            result.update(
                {
                    key: tool[key]
                    for key in ("degraded", "strategy", "toolTruncated", "errorCode")
                    if key in tool
                }
            )
            result["retrievalStatus"] = tool.get("status")
            result["toolStatus"] = tool.get("status")
            result["callId"] = item["toolCallId"]
            if definition:
                result["toolKey"] = definition.toolKey
            completed = self.context.tool_results.get(item["toolCallId"])
            if completed:
                result["toolStatus"] = completed.status
                result["summary"] = completed.summary
                result["sourceType"] = completed.sourceType
            if item["status"] in {"done", "failed", "interrupted"}:
                result["result"] = {
                    "evidence": [
                        {
                            **self.context.public_sources.get(key, {}),
                            **self.context.snapshots[key],
                        }
                        for key in item["evidenceIds"]
                        if key in self.context.snapshots
                    ]
                }
                if completed and completed.sourceType == "mcp":
                    result["result"]["content"] = completed.modelContent
        return result

    def public(self):
        return [self.visible(item) for item in self.blocks]
