"""由联调测试或可信宿主通过 stdin 注入凭据，输出脱敏契约回执。"""

import asyncio
import json
import sys

import httpx
from knowledge_tool import KnowledgeGateway, TrustedContext, TurnReferences


async def run(config):
    async with httpx.AsyncClient(
        base_url=config["origin"],
        trust_env=False,
        limits=httpx.Limits(max_connections=4, max_keepalive_connections=2),
    ) as client:
        gateway = KnowledgeGateway(client, config["serviceId"], config["serviceToken"])
        context = TrustedContext.from_grant(config["grant"], trace_id=config["traceId"])
        references = TurnReferences()
        tool = gateway.bind(context, references)
        first = await tool("PostgreSQL backup")
        second = await tool("PostgreSQL backup restore")
        assert first["status"] == second["status"] == "READY", (first, second)
        a, b = first["evidence"][0]["evidenceId"], second["evidence"][0]["evidenceId"]
        assert a != b
        references.validate([a, b])
        extractive_example = f"资料摘录：{first['evidence'][0]['content'][:80]} [{a}]"
        references.validate_answer(extractive_example)
        return {
            "statuses": [first["status"], second["status"]],
            "traceId": context.trace_id,
            "requestIds": [first["requestId"], second["requestId"]],
            "uniqueCitationIds": [a, b],
            "extractiveCitationValidated": True,
            "note": "真实 Python→Java→PG/MinIO/模型HTTP桩；摘录渲染不等于 LangGraph 主 Agent 生成答案",
        }


if __name__ == "__main__":
    print(json.dumps(asyncio.run(run(json.load(sys.stdin))), ensure_ascii=False))
