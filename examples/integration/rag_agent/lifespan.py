"""接入宿主 FastAPI lifespan；不开放匿名用户代理或另建 Agent 主循环。"""

import os
from contextlib import asynccontextmanager

import httpx
from fastapi import FastAPI
from knowledge_tool import KnowledgeGateway


@asynccontextmanager
async def lifespan(app: FastAPI):
    async with httpx.AsyncClient(
        base_url=os.environ["JAVA_RAG_ORIGIN"],
        limits=httpx.Limits(max_connections=16, max_keepalive_connections=8),
        timeout=20,
        follow_redirects=False,
        trust_env=False,
    ) as client:
        app.state.knowledge_gateway = KnowledgeGateway(
            client,
            os.environ["RAG_SERVICE_ID"],
            os.environ["RAG_SERVICE_TOKEN"],
        )
        try:
            yield
        finally:
            del app.state.knowledge_gateway


# 将 lifespan 合并到已有应用；工具用 bind(可信用户上下文, 本回合引用表) 取得。
# 宿主负责认证、授权委托刷新及图恢复前清理旧敏感工具消息。
app = FastAPI(lifespan=lifespan)
