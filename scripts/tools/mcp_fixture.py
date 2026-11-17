"""仅开发/测试用只读 MCP；启动前设置 ATLAS_TOOL_FIXTURE=true。"""

import asyncio
import hashlib
import os

from mcp.server.fastmcp import Context, FastMCP
from mcp.types import ImageContent, ToolAnnotations
from pydantic import BaseModel


class WindowResult(BaseModel):
    query: str
    window: str
    password: str
    nested: dict[str, str]


def create_fixture(port=8765):
    server = FastMCP(
        "Atlas read-only development fixture",
        host="127.0.0.1",
        port=port,
        stateless_http=True,
        json_response=True,
    )
    read_only = ToolAnnotations(
        readOnlyHint=True, destructiveHint=False, idempotentHint=True
    )

    @server.tool(annotations=read_only)
    def lookup(query: str) -> str:
        """返回只读测试资料。"""
        return f"测试资料：{query} 的服务窗口为 09:00–18:00。"

    @server.tool(annotations=read_only)
    def structured(query: str) -> WindowResult:
        """返回结构化测试结果，包含必须被平台隐藏的敏感字段。"""
        return WindowResult(
            query=query,
            window="09:00–18:00",
            password="fixture-sensitive-value",
            nested={"access_token": "fixture-private-token"},
        )

    @server.tool(annotations=read_only)
    def principal_hash(ctx: Context) -> dict:
        """测试凭证隔离，只返回服务看到的认证头单向哈希。"""
        request = ctx.request_context.request
        value = request.headers.get("authorization", "") if request else ""
        return {"principalHash": hashlib.sha256(value.encode()).hexdigest()}

    @server.tool(annotations=read_only)
    async def slow(seconds: float = 5) -> dict:
        """模拟可取消的慢只读请求。"""
        await asyncio.sleep(min(60, max(0, seconds)))
        return {"finished": True}

    @server.tool(annotations=read_only)
    def large(count: int = 1000) -> dict:
        """模拟必须结构化限长的大结果。"""
        return {
            "rows": [
                {"index": i, "text": "测试结果" * 10}
                for i in range(min(3000, max(0, count)))
            ]
        }

    @server.tool(annotations=read_only)
    def business_error() -> dict:
        """模拟远端业务错误。"""
        raise ValueError("fixture business error")

    @server.tool(annotations=read_only)
    def unsupported() -> list[ImageContent]:
        """模拟当前平台不支持的图片内容。"""
        return [ImageContent(type="image", mimeType="image/png", data="AA==")]

    return server


if __name__ == "__main__":
    if os.getenv("ATLAS_TOOL_FIXTURE") != "true":
        raise SystemExit(
            "This fixture is development-only. Set ATLAS_TOOL_FIXTURE=true."
        )
    create_fixture(int(os.getenv("ATLAS_TOOL_FIXTURE_PORT", "8765"))).run(
        transport="streamable-http"
    )
