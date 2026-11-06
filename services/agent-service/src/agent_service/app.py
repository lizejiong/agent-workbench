"""生产入口：uvicorn agent_service.app:app；连接池/模型/检查点在 lifespan 管理。"""

import asyncio
import json
import re
import uuid
from contextlib import AsyncExitStack, asynccontextmanager

import httpx
from anyio import CancelScope
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse, StreamingResponse
from fastapi.security import HTTPBasic
from langchain_openai import ChatOpenAI
from langgraph.checkpoint.sqlite.aio import AsyncSqliteSaver
from rag_common.result import Result
from rag_common.web import install_web
from starlette.datastructures import MutableHeaders

from agent_service.config import Settings
from agent_service.contracts import AgentError, AgentReply, ChatRequest
from agent_service.graph import build_graph
from agent_service.knowledge.adapter import KnowledgeGateway
from agent_service.runtime import AgentRuntime


class PrivateResponses:
    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        async def private_send(message):
            if message["type"] == "http.response.start":
                MutableHeaders(scope=message)["Cache-Control"] = "no-store"
            await send(message)

        await self.app(
            scope, receive, private_send if scope["type"] == "http" else send
        )


def create_app(
    settings: Settings | None = None, *, java_transport=None, model_transport=None
):
    @asynccontextmanager
    async def lifespan(app: FastAPI):
        config = settings or Settings.from_env()
        config.state_dir.mkdir(parents=True, exist_ok=True)
        async with AsyncExitStack() as stack:
            java = await stack.enter_async_context(
                httpx.AsyncClient(
                    base_url=config.java_origin,
                    limits=httpx.Limits(
                        max_connections=16, max_keepalive_connections=8
                    ),
                    timeout=httpx.Timeout(10, connect=3, pool=2),
                    follow_redirects=False,
                    trust_env=False,
                    transport=java_transport,
                )
            )
            model_http = await stack.enter_async_context(
                httpx.AsyncClient(
                    limits=httpx.Limits(
                        max_connections=16, max_keepalive_connections=8
                    ),
                    timeout=httpx.Timeout(40, connect=3, pool=2),
                    follow_redirects=False,
                    trust_env=False,
                    transport=model_transport,
                )
            )
            # ChatOpenAI 同时构造同步/异步 SDK 客户端；同步客户端也显式关闭，
            # 不让 SDK 从宿主环境另建带代理的隐式连接池。
            model_sync = stack.enter_context(
                httpx.Client(
                    follow_redirects=False,
                    trust_env=False,
                    timeout=40,
                )
            )
            saver = await stack.enter_async_context(
                AsyncSqliteSaver.from_conn_string(
                    str(config.state_dir / "checkpoints.sqlite")
                )
            )
            await saver.setup()
            # 新执行从 Java 业务历史重建，不恢复上次进程遗留的工具节点。
            # 一个 state_dir 仅供一个进程使用；通过 Saver API 清除旧执行快照。
            while True:
                old_threads = {
                    item.config["configurable"]["thread_id"]
                    async for item in saver.alist(None, limit=100)
                }
                if not old_threads:
                    break
                for thread_id in old_threads:
                    await saver.adelete_thread(thread_id)
            model = ChatOpenAI(
                model=config.model,
                api_key=config.model_api_key,
                base_url=config.model_base_url,
                max_retries=0,
                max_completion_tokens=1024,
                timeout=40,
                http_async_client=model_http,
                http_client=model_sync,
                use_responses_api=False,
            )
            graph, prompt_hash = build_graph(model, saver)
            gateway = KnowledgeGateway(
                java,
                config.service_id,
                config.service_token,
                token_budget=config.tool_byte_budget,
            )
            app.state.runtime = AgentRuntime(config, gateway, graph, prompt_hash)
            try:
                yield
            finally:
                del app.state.runtime

    app = FastAPI(title="Agent Workbench Python Agent", lifespan=lifespan)
    install_web(app)
    app.add_middleware(PrivateResponses)
    basic = HTTPBasic(auto_error=False)

    async def identity(request):
        credentials = await basic(request)
        if credentials is None:
            raise AgentError(401, "AUTHENTICATION_REQUIRED", "请使用现有 Java 账号认证")
        traceparent = request.headers.get("traceparent", "")
        valid = re.fullmatch(r"00-([0-9a-f]{32})-([0-9a-f]{16})-0[01]", traceparent)
        trace_id = (
            valid[1]
            if valid and valid[1] != "0" * 32 and valid[2] != "0" * 16
            else uuid.uuid4().hex
        )
        return httpx.BasicAuth(credentials.username, credentials.password), trace_id

    @app.get("/health")
    async def health():
        # 仅进程存活；不声称模型、Java 或数据库依赖已验收。
        return Result.success({"status": "UP", "service": "agent-service"})

    @app.post("/api/agent/chat", response_model=Result[AgentReply])
    async def chat(body: ChatRequest, request: Request):
        auth, trace_id = await identity(request)
        task = asyncio.create_task(
            app.state.runtime.run(
                body,
                auth,
                trace_id,
            )
        )

        async def disconnect():
            while not await request.is_disconnected():  # noqa: ASYNC110
                await asyncio.sleep(0.05)

        watcher = asyncio.create_task(disconnect())
        try:
            await asyncio.wait({task, watcher}, return_when=asyncio.FIRST_COMPLETED)
            if watcher.done() and not task.done():
                task.cancel()
                raise AgentError(499, "CLIENT_DISCONNECTED", "客户端已断开")
            result = await task
            return JSONResponse(
                Result.success(AgentReply.model_validate(result)).model_dump(
                    mode="json"
                ),
                headers={"Cache-Control": "no-store", "X-Trace-Id": trace_id},
            )
        finally:
            watcher.cancel()
            task.cancel()
            await asyncio.gather(watcher, task, return_exceptions=True)

    @app.post("/api/agent/chat/stream")
    async def stream(body: ChatRequest, request: Request):
        version = request.headers.get("X-Agent-Stream-Version", "1")
        if version not in {"1", "2"}:
            raise AgentError(400, "STREAM_VERSION_UNSUPPORTED", "不支持的事件协议版本")
        if not body.conversationId or not body.requestId or not body.messageId:
            raise AgentError(
                400,
                "STREAM_IDENTIFIERS_REQUIRED",
                "流式请求必须提供会话、请求和消息标识",
            )
        auth, trace_id = await identity(request)
        queue = asyncio.Queue(maxsize=32)
        terminal = False

        async def emit(event, data):
            nonlocal terminal
            if event == "done":
                terminal = True
            async with asyncio.timeout(1):
                await queue.put((event, data))

        async def produce():
            try:
                await app.state.runtime.run(
                    body, auth, trace_id, emit, protocol_version=int(version)
                )
            except AgentError as exc:
                if not terminal:
                    await emit(
                        "error",
                        {
                            "code": exc.code,
                            "httpStatus": exc.status_code,
                            "persisted": False,
                        },
                    )
            except asyncio.CancelledError:
                raise
            except Exception:
                if not terminal:
                    await emit(
                        "error",
                        {
                            "code": "AGENT_UNAVAILABLE",
                            "httpStatus": 503,
                            "persisted": False,
                        },
                    )
            finally:
                # 断连取消时不能在已无人消费的有界队列中等待。
                if not asyncio.current_task().cancelling():
                    try:
                        async with asyncio.timeout(1):
                            await queue.put(None)
                    except TimeoutError:
                        pass

        task = asyncio.create_task(produce())

        async def watch_disconnect():
            while not await request.is_disconnected():  # noqa: ASYNC110
                await asyncio.sleep(0.05)

        watcher = asyncio.create_task(watch_disconnect())
        first_event = asyncio.create_task(queue.get())
        try:
            await asyncio.wait(
                {first_event, watcher}, return_when=asyncio.FIRST_COMPLETED
            )
            if watcher.done():
                raise AgentError(499, "CLIENT_DISCONNECTED", "客户端已断开")
            first = await first_event
            if first is None or first[0] == "error":
                data = first[1] if first else {}
                raise AgentError(
                    data.get("httpStatus", 503),
                    data.get("code", "AGENT_UNAVAILABLE"),
                    "无法开始本轮对话",
                )
        except BaseException:
            task.cancel()
            await asyncio.gather(task, return_exceptions=True)
            raise
        finally:
            watcher.cancel()
            first_event.cancel()
            await asyncio.gather(watcher, first_event, return_exceptions=True)

        def frame(item):
            event, data = item
            return f"event: {event}\ndata: {json.dumps(data, ensure_ascii=False)}\n\n"

        async def content():
            try:
                yield frame(first)
                while True:
                    try:
                        item = await asyncio.wait_for(queue.get(), timeout=1)
                    except TimeoutError:
                        if task.done() and queue.empty():
                            break
                        yield ": heartbeat\n\n"
                        continue
                    if item is None:
                        break
                    yield frame(item)
            finally:
                # StreamingResponse 的断连使用 AnyIO 取消域；保护短结算，避免
                # 对 producer 的重复取消打断 HTTP 关闭与 Java 部分结果保存。
                with CancelScope(shield=True):
                    task.cancel()
                    await asyncio.gather(task, return_exceptions=True)

        return StreamingResponse(
            content(),
            media_type="text/event-stream",
            headers={
                "Cache-Control": "no-store",
                "X-Accel-Buffering": "no",
                "X-Trace-Id": trace_id,
                "X-Agent-Stream-Version": version,
            },
        )

    return app


app = create_app()
