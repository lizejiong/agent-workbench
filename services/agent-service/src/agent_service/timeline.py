"""稳定内容块与 SSE 投影；不暴露推理正文、原始工具异常或服务凭据。"""

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

    def bind(self, run, seq=0):
        self.run = run
        self.seq = seq

    async def _send(self, event, data):
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
            await self.sink(event, {**data, **envelope} if self.version == 2 else data)

    async def __call__(self, event, data, *, block=None):
        if self.version == 1:
            # 旧客户端保持 meta/delta/tool/error/done，不发送新事件的重复副本。
            if event not in {"block", "finish", "cancel", "hint"}:
                await self._send(event, data)
            return
        if event == "delta":
            await self._send(
                "message",
                {"type": "answer", "blockId": data["blockId"], "delta": data["text"]},
            )
        elif event == "tool":
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
            if call["name"] != "search_knowledge":
                continue
            if self.find(call["id"]):
                raise AgentError(502, "AGENT_TOOL_ID_CONFLICT", "工具调用标识重复")
            for item in self.blocks:
                if item["kind"] == "answer" and item["status"] == "streaming":
                    item["status"] = "done"
            item = {
                "blockId": block_id(),
                "kind": "tool",
                "toolCallId": call["id"],
                "name": "search_knowledge",
                "status": "pending",
                "batchId": batch,
                "callIndex": index,
                "arguments": safe_query(call.get("args", {}).get("query")),
                "evidenceIds": [],
            }
            self.context.tools.setdefault(
                call["id"],
                {
                    "toolCallId": call["id"],
                    "name": "search_knowledge",
                    "status": "RUNNING",
                },
            )
            self.blocks.append(item)
            created.append(item)
        self.check_budget()
        return created

    def find(self, call_id):
        return next((b for b in self.blocks if b.get("toolCallId") == call_id), None)

    def tool(self, data):
        item = self.find(data["toolCallId"])
        if item is None:
            item = self.prepare(
                [{"id": data["toolCallId"], "name": data["name"], "args": {}}]
            )[0]
        if data["status"] == "RUNNING":
            item["status"] = "running"
            item["startedAt"] = datetime.now(UTC).isoformat()
            self.clock[data["toolCallId"]] = time.monotonic()
        else:
            item["status"] = "failed" if data["status"] == "UNAVAILABLE" else "done"
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
            result["displayName"] = "知识库检索"
            tool = self.context.tools.get(item["toolCallId"], {})
            result.update(
                {
                    key: tool[key]
                    for key in ("degraded", "strategy", "toolTruncated", "errorCode")
                    if key in tool
                }
            )
            result["retrievalStatus"] = tool.get("status")
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
        return result

    def public(self):
        return [self.visible(item) for item in self.blocks]
