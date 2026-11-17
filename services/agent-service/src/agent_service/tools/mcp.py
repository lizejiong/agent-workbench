"""官方 mcp 1.26.0 Streamable HTTP；每次操作独立 session，禁止跨用户复用。"""

import asyncio
import os
from contextlib import asynccontextmanager
from datetime import timedelta
from urllib.parse import urlsplit

import httpx
from mcp import ClientSession
from mcp.client.streamable_http import streamablehttp_client

from agent_service.contracts import AgentError
from agent_service.knowledge.adapter import digest, encode
from agent_service.tools.contracts import ToolDefinition, ToolResult, model_name
from agent_service.tools.executor import bounded


def remote_schema(tool):
    return {
        "name": tool.name,
        "description": tool.description or tool.name,
        "inputSchema": tool.inputSchema,
        "outputSchema": tool.outputSchema,
        "readOnlyHint": bool(tool.annotations and tool.annotations.readOnlyHint),
    }


def schema_hash(tool):
    import json

    return digest(
        json.dumps(
            remote_schema(tool),
            sort_keys=True,
            separators=(",", ":"),
            ensure_ascii=False,
        ).encode()
    )


class ResponseBudget(httpx.AsyncByteStream):
    def __init__(self, stream):
        self.stream = stream

    async def __aiter__(self):
        size = 0
        async for part in self.stream:
            size += len(part)
            if size > 1048576:
                raise ValueError("MCP_RESPONSE_BUDGET")
            yield part

    async def aclose(self):
        await self.stream.aclose()


class LimitedTransport(httpx.AsyncHTTPTransport):
    async def handle_async_request(self, request):
        response = await super().handle_async_request(request)
        response.stream = ResponseBudget(response.stream)
        return response


class MCPManager:
    def __init__(self, allowed_origins=(), *, transport=None):
        self.allowed_origins = set(allowed_origins)
        self.transport = transport
        self.active = set()
        self.closed = False

    def credentials(self, connection):
        parsed = urlsplit(connection["url"])
        origin = f"{parsed.scheme}://{parsed.netloc}"
        if (
            origin not in self.allowed_origins
            or parsed.scheme not in {"http", "https"}
            or not parsed.hostname
            or parsed.username
            or parsed.password
            or parsed.query
            or parsed.fragment
        ):
            raise AgentError(403, "MCP_ENDPOINT_DENIED", "MCP 地址不在部署访问策略中")
        ref = connection.get("credentialRef")
        if not ref:
            return {}
        import re

        if not re.fullmatch(r"env:[A-Z][A-Z0-9_]{1,100}", ref):
            raise AgentError(503, "MCP_CREDENTIAL_UNAVAILABLE", "凭证引用不可用")
        secret = os.environ.get(ref[4:], "")
        if not secret or "\r" in secret or "\n" in secret:
            raise AgentError(503, "MCP_CREDENTIAL_UNAVAILABLE", "凭证引用不可用")
        return {"Authorization": "Bearer " + secret}

    @asynccontextmanager
    async def session(self, connection, seconds=10):
        if self.closed:
            raise AgentError(503, "MCP_UNAVAILABLE", "MCP manager 已关闭")
        headers = self.credentials(connection)
        task = asyncio.current_task()
        self.active.add(task)

        def client_factory(headers=None, timeout=None, auth=None):
            return httpx.AsyncClient(
                headers=headers,
                timeout=timeout,
                auth=auth,
                follow_redirects=False,
                trust_env=False,
                transport=self.transport or LimitedTransport(retries=0),
            )

        try:
            # SDK 的 AnyIO cancel scopes 必须在创建它们的同一 task 内退出。
            async with streamablehttp_client(
                connection["url"],
                headers=headers,
                timeout=seconds,
                sse_read_timeout=seconds,
                httpx_client_factory=client_factory,
            ) as (read, write, _):
                async with ClientSession(
                    read, write, read_timeout_seconds=timedelta(seconds=seconds)
                ) as session:
                    await session.initialize()
                    yield session
        finally:
            self.active.discard(task)

    async def tools(self, session):
        tools, cursor, seen = [], None, set()
        for _ in range(16):
            page = await session.list_tools(cursor=cursor)
            tools.extend(page.tools)
            if len(tools) > 128:
                raise ValueError("MCP_TOOL_BUDGET")
            cursor = page.nextCursor
            if not cursor:
                if len({t.name for t in tools}) != len(tools):
                    raise ValueError("MCP_DUPLICATE_TOOL")
                return tools
            if cursor in seen:
                raise ValueError("MCP_CURSOR_INVALID")
            seen.add(cursor)
        raise ValueError("MCP_PAGE_BUDGET")

    async def discover(self, connection):
        async with asyncio.timeout(15):
            async with self.session(connection) as session:
                tools = await self.tools(session)
                result = []
                for tool in tools:
                    key = f"mcp:{connection['serverId']}:{tool.name}"
                    definition = ToolDefinition(
                        toolKey=key,
                        modelName=model_name(key),
                        displayName=tool.title or tool.name,
                        description=(tool.description or tool.name)[:2000],
                        inputSchema=tool.inputSchema,
                        outputSchema=tool.outputSchema,
                        kind="mcp",
                        serverId=connection["serverId"],
                        remoteToolName=tool.name,
                        configVersion=connection["configVersion"],
                        schemaHash=schema_hash(tool),
                    )
                    result.append(
                        {
                            **definition.model_dump(),
                            "readOnlyHint": remote_schema(tool)["readOnlyHint"],
                        }
                    )
                if len(encode(result)) > 524288:
                    raise ValueError("MCP_DISCOVERY_BUDGET")
                return result

    async def execute(self, definition, call, context):
        connection = await context.services.control.connection(context, call)
        timeout = min(definition.timeoutMs / 1000, 40)
        async with self.session(connection, timeout) as session:
            tools = await self.tools(session)
            remote = next(
                (t for t in tools if t.name == definition.remoteToolName), None
            )
            if remote is None or schema_hash(remote) != definition.schemaHash:
                return ToolResult(
                    callId=call.callId, status="denied", errorCode="MCP_SCHEMA_CHANGED"
                )
            # 发现/连接可能耗时；真正 call_tool 前再次核验当前授权与运行状态。
            await context.services.control.authorize(context, call)
            result = await session.call_tool(
                definition.remoteToolName,
                call.arguments,
                read_timeout_seconds=timedelta(seconds=timeout),
            )
        if any(content.type != "text" for content in result.content):
            return ToolResult(
                callId=call.callId, status="failed", errorCode="MCP_CONTENT_UNSUPPORTED"
            )
        if result.isError:
            return ToolResult(
                callId=call.callId,
                status="failed",
                errorCode="MCP_BUSINESS_ERROR",
                summary="远端工具返回业务错误",
            )
        payload = result.structuredContent
        if payload is None:
            import json

            text = "\n".join(item.text for item in result.content)
            try:
                parsed = json.loads(text)
                payload = parsed if isinstance(parsed, (dict, list)) else {"text": text}
            except ValueError:
                payload = {"text": text}
        # 即使远端把当前请求的凭证回显在无标签文本里，也不能流入模型或浏览器。
        secret = (self.credentials(connection).get("Authorization") or "")[7:]

        def scrub(value):
            if isinstance(value, dict):
                return {scrub(k): scrub(v) for k, v in value.items()}
            if isinstance(value, list):
                return [scrub(v) for v in value]
            return (
                value.replace(secret, "[已隐藏]")
                if secret and isinstance(value, str)
                else value
            )

        payload = scrub(payload)
        if definition.outputSchema and result.structuredContent is not None:
            from jsonschema import Draft202012Validator

            try:
                Draft202012Validator(definition.outputSchema).validate(payload)
            except Exception:
                return ToolResult(
                    callId=call.callId, status="failed", errorCode="MCP_OUTPUT_INVALID"
                )
        payload, truncated = bounded(payload, definition.maxResultBytes - 768)
        source_id = "M" + call.callId.removeprefix("T")
        return ToolResult(
            callId=call.callId,
            status="success" if payload and payload != {"text": ""} else "empty",
            modelContent={"sourceId": source_id, "content": payload},
            evidenceRefs=[source_id],
            sourceType="mcp",
            truncated=truncated,
            summary="已返回 MCP 只读结果" + ("（已截取）" if truncated else ""),
        )

    async def aclose(self):
        self.closed = True
        tasks = list(self.active - {asyncio.current_task()})
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
