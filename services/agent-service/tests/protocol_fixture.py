"""真实图使用的 HTTP 协议桩；不冒充供应商效果或真实业务系统。"""

import asyncio
import base64
import json
import uuid
from datetime import UTC, datetime, timedelta

import httpx

from agent_service.knowledge.adapter import digest

KB_ID = str(uuid.UUID(int=10))
AUTH = ("alice", "test-password")


class ProtocolFixture:
    def __init__(self):
        self.grants = {}
        self.java_requests = []
        self.model_requests = []
        self.model_headers = []
        self.status = "READY"
        self.denied = False
        self.java_down = False
        self.model_down = False
        self.invalid_citation = False
        self.block_retrieval = False
        self.started = asyncio.Event()
        self.closed = asyncio.Event()
        self.conversations = {}
        self.runs = {}
        self.messages = {}
        self.block_answer = False
        self.partial_started = asyncio.Event()
        self.answer_closed = asyncio.Event()
        self.answer_delay = 0.01
        self.direct_answer = False
        self.truncate_answer = False

    async def java(self, request):
        self.java_requests.append(request)
        path = request.url.path
        if (
            path == "/api/conversations"
            or path.startswith("/api/conversations/")
            or path.startswith("/internal/agent/")
        ):
            return await self.conversation(request)
        if request.method == "DELETE":
            return httpx.Response(204)
        if path == "/api/rag/delegations":
            header = request.headers.get("Authorization", "")
            if not header.startswith("Basic "):
                return httpx.Response(401)
            username, password = base64.b64decode(header[6:]).decode().split(":", 1)
            if password != "test-password":
                return httpx.Response(401)
            if self.denied:
                return httpx.Response(403, text="SQL and raw-error must stay private")
            body = json.loads(request.content)
            token = "rd_" + uuid.uuid4().hex + "x" * 11
            grant = {
                "delegationId": str(uuid.uuid4()),
                "delegationToken": token,
                "serviceId": body["serviceId"],
                "tenantId": "tenant-a",
                "userId": username,
                "agentSessionId": body["agentSessionId"],
                "kbIds": body["kbIds"] if body["kbIds"] is not None else [KB_ID],
                "documentIds": body.get("documentIds"),
                "expiresAt": (datetime.now(UTC) + timedelta(minutes=5)).isoformat(),
            }
            self.grants[token] = grant
            return httpx.Response(201, json={"code": "0", "data": grant})
        if path.endswith("/cancel"):
            return httpx.Response(200, json={"code": "0"})
        assert path == "/internal/rag/retrieve"
        assert request.headers["Authorization"] == "Bearer " + "s" * 32
        grant = self.grants[request.headers["X-Rag-Delegation"]]
        body = json.loads(request.content)
        assert body["kbIds"] == grant["kbIds"]
        assert (
            not {"tenantId", "userId", "agentSessionId", "conversationId"} & body.keys()
        )
        self.started.set()
        if self.block_retrieval:
            try:
                await asyncio.Event().wait()
            finally:
                self.closed.set()
        if self.java_down:
            return httpx.Response(503, text="SQL password=secret http://private-host")
        trace = request.headers["traceparent"].split("-")[1]
        content = "授权资料：使用 pg_dump 进行 PostgreSQL 备份。"
        rows = (
            [
                {
                    "evidenceId": "E1",
                    "kbId": grant["kbIds"][0],
                    "documentId": str(uuid.UUID(int=20)),
                    "chunkId": str(uuid.UUID(int=21)),
                    "generation": 1,
                    "documentName": "备份说明",
                    "content": content,
                    "sourceMetadata": {"pageNumber": 2},
                    "snapshotHash": digest(content),
                    "sourceContentHash": digest(content),
                    "truncated": False,
                    "adjacent": False,
                }
            ]
            if self.status == "READY"
            else []
        )
        return httpx.Response(
            200,
            json={
                "code": "0",
                "data": {
                    "status": self.status,
                    "requestId": request.headers["X-Request-Id"],
                    "traceId": trace,
                    "strategy": "VECTOR",
                    "rerankStatus": "DISABLED",
                    "degraded": False,
                    "versions": {"pipeline": "fixture-v1"},
                    "contextHash": "c" * 64,
                    "clarificationQuestion": "请补充产品名称。",
                    "evidence": rows,
                },
            },
        )

    async def model(self, request):
        assert request.url.path.endswith("/chat/completions")
        body = json.loads(request.content)
        self.model_requests.append(body)
        self.model_headers.append(dict(request.headers))
        tools = body["tools"]
        assert len(tools) == 1
        assert set(tools[0]["function"]["parameters"]["properties"]) == {"query"}
        if self.model_down:
            return httpx.Response(
                503,
                json={
                    "error": {
                        "message": "password=secret http://private-host",
                        "type": "server_error",
                    }
                },
            )
        messages = body["messages"]
        last_user = max(i for i, item in enumerate(messages) if item["role"] == "user")
        tool_messages = [
            item for item in messages[last_user + 1 :] if item["role"] == "tool"
        ]
        if not tool_messages and self.direct_answer:
            message = {"role": "assistant", "content": "根据刚才的回答继续说明。"}
        elif not tool_messages:
            count = 2 if "两次" in messages[last_user]["content"] else 1
            message = {
                "role": "assistant",
                "content": None,
                "tool_calls": [
                    {
                        "id": f"call_{uuid.uuid4().hex}",
                        "type": "function",
                        "function": {
                            "name": "search_knowledge",
                            "arguments": json.dumps(
                                {"query": f"PostgreSQL backup {i}"}
                            ),
                        },
                    }
                    for i in range(count)
                ],
            }
        else:
            results = [json.loads(item["content"]) for item in tool_messages]
            evidence = [e for item in results for e in item["evidence"]]
            if self.invalid_citation:
                answer = "错误引用 [E1]"
            elif evidence:
                answer = "按资料执行备份。" + "".join(
                    f"[{e['evidenceId']}]" for e in evidence
                )
            else:
                status = results[0]["status"]
                answer = {
                    "NO_EVIDENCE": "当前授权资料不足。",
                    "NEEDS_CLARIFICATION": results[0].get("clarificationQuestion", ""),
                    "UNAVAILABLE": "检索暂不可用，请稍后重试。",
                }[status]
            message = {"role": "assistant", "content": answer}
        if body.get("stream"):
            owner = self

            class Chunks(httpx.AsyncByteStream):
                async def __aiter__(self):
                    def frame(delta, finish=None):
                        value = {
                            "id": "chatcmpl-fixture",
                            "object": "chat.completion.chunk",
                            "created": 1,
                            "model": "fixture-agent",
                            "choices": [
                                {"index": 0, "delta": delta, "finish_reason": finish}
                            ],
                        }
                        return (
                            "data: " + json.dumps(value, ensure_ascii=False) + "\n\n"
                        ).encode()

                    if message.get("tool_calls"):
                        calls = [
                            {"index": i, **c}
                            for i, c in enumerate(message["tool_calls"])
                        ]
                        yield frame({"role": "assistant", "tool_calls": calls})
                        yield frame({}, "tool_calls")
                    else:
                        text = message["content"]
                        try:
                            for i in range(0, len(text), 8):
                                yield frame({"content": text[i : i + 8]})
                                if owner.truncate_answer:
                                    return
                                if owner.block_answer:
                                    owner.partial_started.set()
                                    await asyncio.Event().wait()
                                await asyncio.sleep(owner.answer_delay)
                            yield frame({}, "stop")
                        finally:
                            owner.answer_closed.set()
                    yield b"data: [DONE]\n\n"

            return httpx.Response(
                200, headers={"Content-Type": "text/event-stream"}, stream=Chunks()
            )
        return httpx.Response(
            200,
            json={
                "id": "chatcmpl-fixture",
                "object": "chat.completion",
                "created": 1,
                "model": "fixture-agent",
                "choices": [
                    {
                        "index": 0,
                        "message": message,
                        "finish_reason": "tool_calls"
                        if "tool_calls" in message
                        else "stop",
                    }
                ],
                "usage": {
                    "prompt_tokens": 10,
                    "completion_tokens": 10,
                    "total_tokens": 20,
                },
            },
        )

    async def conversation(self, request):
        """仅模拟 Java 新契约；实际 SQL/隔离语义由 Java PostgreSQL 集成测试验证。"""
        path, method = request.url.path, request.method
        body = json.loads(request.content) if request.content else {}
        if path.startswith("/api/"):
            header = request.headers.get("Authorization", "")
            if not header.startswith("Basic "):
                return httpx.Response(401)
            username, password = base64.b64decode(header[6:]).decode().split(":", 1)
            if password != AUTH[1]:
                return httpx.Response(401)
            if path == "/api/conversations":
                conversation_id = str(uuid.uuid4())
                self.conversations[conversation_id] = {
                    "id": conversation_id,
                    "owner": username,
                    "title": "新对话",
                    "kbIds": body.get("kbIds") or [KB_ID],
                    "documentIds": body.get("documentIds"),
                }
                self.messages[conversation_id] = []
                return httpx.Response(
                    201, json={"code": "0", "data": self.conversations[conversation_id]}
                )
            conversation_id = path.split("/")[3]
            conversation = self.conversations.get(conversation_id)
            if not conversation or conversation["owner"] != username or self.denied:
                return httpx.Response(404)
            if path.endswith("/delegations"):
                token = "rd_" + uuid.uuid4().hex + "x" * 11
                grant = {
                    "delegationId": str(uuid.uuid4()),
                    "delegationToken": token,
                    "serviceId": body["serviceId"],
                    "tenantId": "tenant-a",
                    "userId": username,
                    "agentSessionId": conversation_id,
                    "kbIds": conversation["kbIds"],
                    "documentIds": conversation["documentIds"],
                    "expiresAt": (datetime.now(UTC) + timedelta(minutes=5)).isoformat(),
                }
                self.grants[token] = grant
                return httpx.Response(201, json={"code": "0", "data": grant})
            raise AssertionError(path)
        assert request.headers["Authorization"] == "Bearer " + "s" * 32
        grant = self.grants[request.headers["X-Rag-Delegation"]]
        conversation_id = path.split("/")[4]
        assert conversation_id == grant["agentSessionId"]
        rows = self.messages[conversation_id]
        if path.endswith("/history"):
            history = [
                {
                    **{k: row[k] for k in ("id", "role", "content", "runId")},
                    "evidence": row.get("evidence", []),
                }
                for row in rows
                if row["status"] == "COMPLETED"
                and (self.status == "READY" or row["role"] == "user")
            ]
            return httpx.Response(200, json={"code": "0", "data": history})
        if method == "POST":
            previous = next(
                (
                    r
                    for r in self.runs.values()
                    if r["conversationId"] == conversation_id
                    and r["requestId"] == body["requestId"]
                ),
                None,
            )
            if previous:
                if (
                    previous["userMessageId"] != body["messageId"]
                    or previous["question"] != body["question"]
                ):
                    return httpx.Response(409, json={"code": "AGENT_REQUEST_CONFLICT"})
                return httpx.Response(
                    200, json={"code": "0", "data": {**previous, "created": False}}
                )
            if any(
                r["conversationId"] == conversation_id and r["status"] == "RUNNING"
                for r in self.runs.values()
            ):
                return httpx.Response(409, json={"code": "SESSION_BUSY"})
            run_id, assistant = str(uuid.uuid4()), str(uuid.uuid4())
            run = {
                "runId": run_id,
                "conversationId": conversation_id,
                "requestId": body["requestId"],
                "userMessageId": body["messageId"],
                "assistantMessageId": assistant,
                "question": body["question"],
                "traceId": request.headers["traceparent"].split("-")[1],
                "status": "RUNNING",
                "checkpointSequence": 0,
                "deadline": body["deadline"],
                "created": True,
            }
            self.runs[run_id] = run
            rows.extend(
                [
                    {
                        "id": body["messageId"],
                        "runId": run_id,
                        "role": "user",
                        "content": body["question"],
                        "status": "COMPLETED",
                    },
                    {
                        "id": assistant,
                        "runId": run_id,
                        "role": "assistant",
                        "content": "",
                        "status": "RUNNING",
                        "citations": [],
                        "evidence": [],
                        "tools": [],
                    },
                ]
            )
            return httpx.Response(200, json={"code": "0", "data": run})
        run = self.runs[path.split("/")[-1]]
        row = next(r for r in rows if r["id"] == run["assistantMessageId"])
        if method == "PATCH":
            if run["status"] == "RUNNING":
                run["status"] = body["status"]
            run["checkpointSequence"] = body["sequence"]
            row.update(
                content=body["content"],
                status=run["status"],
                evidence=body["evidence"],
                tools=body["tools"],
                model=body["model"],
                promptVersion=body["promptVersion"],
                promptHash=body["promptHash"],
            )
            row["citations"] = [
                {
                    **s,
                    "documentName": "备份说明",
                    "fileUrl": f"/api/documents/{s['documentId']}/file",
                }
                for s in body["evidence"]
                if s["evidenceId"] in body["citations"]
            ]
            return httpx.Response(200, json={"code": "0", "data": run})
        return httpx.Response(
            200, json={"code": "0", "data": {"run": run, "message": row}}
        )
