"""使用现有 Java Basic 边界签发委托；账号和委托不进入图状态或模型。"""

import asyncio
from contextlib import asynccontextmanager
from uuid import UUID

import httpx

from agent_service.contracts import AgentError


class DelegationClient:
    def __init__(self, client: httpx.AsyncClient, service_id: str):
        self.client = client
        self.service_id = service_id

    @asynccontextmanager
    async def issue(
        self, auth: httpx.BasicAuth, session_id: str, scope: dict | None = None
    ):
        try:
            response = await self.client.post(
                f"/api/conversations/{session_id}/delegations",
                auth=auth,
                json={
                    "serviceId": self.service_id,
                },
            )
        except httpx.HTTPError as exc:
            raise AgentError(503, "JAVA_UNAVAILABLE", "身份授权服务暂不可用") from exc
        if response.status_code != 201:
            if response.status_code == 401:
                raise AgentError(401, "AUTHENTICATION_REQUIRED", "请使用有效账号认证")
            if response.status_code in {403, 404}:
                raise AgentError(404, "SCOPE_NOT_ACCESSIBLE", "会话或知识范围不可访问")
            if response.status_code in {400, 409}:
                raise AgentError(409, "SCOPE_INVALID", "知识范围配置已变化，请重新确认")
            raise AgentError(503, "JAVA_UNAVAILABLE", "身份授权服务暂不可用")
        try:
            envelope = response.json()
            grant = envelope["data"]
            if (
                envelope["code"] != "0"
                or grant["serviceId"] != self.service_id
                or grant["agentSessionId"] != session_id
                or not grant["tenantId"]
                or not grant["userId"]
            ):
                raise ValueError("invalid identity")
            grant_id = str(UUID(grant["delegationId"]))
        except (KeyError, TypeError, ValueError) as exc:
            raise AgentError(502, "INVALID_GRANT", "身份授权响应无效") from exc
        try:
            yield grant
        finally:
            # 撤销的短预算不挤占下一轮，也不掩盖原始取消或故障。
            try:
                async with asyncio.timeout(0.5):
                    await self.client.delete(
                        f"/api/rag/delegations/{grant_id}", auth=auth, timeout=0.45
                    )
            except (httpx.HTTPError, TimeoutError):
                pass
