"""真实调用 Java：用户授权在工具外完成，示例编排不声称是 LangGraph 主 Agent。"""

import asyncio
import json
import os
import uuid

from knowledge_tool import TrustedContext, TurnReferences
from lifespan import app


async def main():
    async with app.router.lifespan_context(app):
        gateway = app.state.knowledge_gateway
        scope = [
            str(uuid.UUID(x.strip()))
            for x in os.environ["RAG_KB_IDS"].split(",")
            if x.strip()
        ]
        # 仅联调 bootstrap 用已有用户 Basic；正式接入由宿主认证边界签发并注入委托。
        response = await gateway.client.post(
            "/api/rag/delegations",
            auth=(os.environ["RAG_USER"], os.environ["RAG_PASSWORD"]),
            json={
                "serviceId": gateway.service_id,
                "agentSessionId": str(uuid.uuid4()),
                "kbIds": scope,
            },
        )
        if response.status_code != 201:
            raise RuntimeError(f"委托签发失败 HTTP {response.status_code}")
        grant = response.json()["data"]
        try:
            context = TrustedContext.from_grant(grant, seconds=30)
            references = TurnReferences()
            search_knowledge = gateway.bind(context, references)
            first = await search_knowledge(
                os.environ.get("RAG_QUERY", "PostgreSQL 如何备份？")
            )
            second = await search_knowledge(
                os.environ.get("RAG_SECOND_QUERY", "PostgreSQL 如何恢复备份？")
            )
            # 示例只展示与业务工具结果组合的位置，不触发任何真实业务写操作。
            business = {"status": "DEMO_ONLY", "note": "此处交给宿主已有业务工具"}
            selected = [e["evidenceId"] for e in first["evidence"]]
            sources = references.validate(selected)
            print(
                json.dumps(
                    {
                        "first": first,
                        "second": second,
                        "business": business,
                        "validatedSourceMappings": sources,
                        "note": "示例取得证据后由宿主 Agent 生成答案；本脚本没有重建 ReAct。",
                    },
                    ensure_ascii=False,
                    indent=2,
                )
            )
        finally:
            await gateway.client.delete(
                f"/api/rag/delegations/{grant['delegationId']}",
                auth=(os.environ["RAG_USER"], os.environ["RAG_PASSWORD"]),
            )


if __name__ == "__main__":
    asyncio.run(main())
