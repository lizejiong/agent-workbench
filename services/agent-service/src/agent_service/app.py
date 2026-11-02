"""生产入口：uvicorn agent_service.app:app；连接池/模型/检查点在 lifespan 管理。"""

import asyncio
import re
import uuid
from contextlib import AsyncExitStack, asynccontextmanager

import aiosqlite
import httpx
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
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
from agent_service.sessions import Sessions


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
            connection = await stack.enter_async_context(
                aiosqlite.connect(str(config.state_dir / "sessions.sqlite"))
            )
            sessions = Sessions(connection)
            await sessions.setup()
            saver = await stack.enter_async_context(
                AsyncSqliteSaver.from_conn_string(
                    str(config.state_dir / "checkpoints.sqlite")
                )
            )
            await saver.setup()
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
            app.state.runtime = AgentRuntime(
                config, gateway, graph, sessions, prompt_hash
            )
            try:
                yield
            finally:
                del app.state.runtime

    app = FastAPI(title="Agent Workbench Python Agent", lifespan=lifespan)
    install_web(app)
    app.add_middleware(PrivateResponses)
    basic = HTTPBasic(auto_error=False)

    @app.get("/health")
    async def health():
        # 仅进程存活；不声称模型、Java 或数据库依赖已验收。
        return Result.success({"status": "UP", "service": "agent-service"})

    @app.post("/api/agent/chat", response_model=Result[AgentReply])
    async def chat(body: ChatRequest, request: Request):
        credentials = await basic(request)
        if credentials is None:
            raise AgentError(401, "AUTHENTICATION_REQUIRED", "请使用现有 Java 账号认证")
        traceparent = request.headers.get("traceparent", "")
        valid_trace = re.fullmatch(
            r"00-([0-9a-f]{32})-([0-9a-f]{16})-0[01]", traceparent
        )
        trace_id = (
            valid_trace[1]
            if valid_trace and valid_trace[1] != "0" * 32 and valid_trace[2] != "0" * 16
            else uuid.uuid4().hex
        )
        task = asyncio.create_task(
            app.state.runtime.run(
                body,
                httpx.BasicAuth(credentials.username, credentials.password),
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

    return app


app = create_app()
