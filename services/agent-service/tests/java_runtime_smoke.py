"""Java 集成验收通过 stdin 启动真实 ASGI 入口；只有模型使用协议桩。"""

import asyncio
import json
import logging
import socket
import sys
from pathlib import Path

import httpx
import uvicorn
from protocol_fixture import ProtocolFixture

from agent_service.app import create_app
from agent_service.config import Settings


async def run(config):
    fixture = ProtocolFixture()
    settings = Settings(
        java_origin=config["origin"],
        service_id=config["serviceId"],
        service_token=config["serviceToken"],
        model_base_url="http://127.0.0.1/v1",
        model="fixture-agent",
        model_api_key="fixture-only-not-a-secret",
        state_dir=Path(config["stateDir"]),
        turn_timeout=20,
    )
    app = create_app(settings, model_transport=httpx.MockTransport(fixture.model))
    listener = socket.socket()
    listener.bind(("127.0.0.1", 0))
    listener.listen(16)
    port = listener.getsockname()[1]
    server = uvicorn.Server(
        uvicorn.Config(
            app,
            log_config=None,
            access_log=False,
            lifespan="on",
        )
    )
    serving = asyncio.create_task(server.serve(sockets=[listener]))
    try:
        async with asyncio.timeout(10):
            while not server.started:  # noqa: ASYNC110
                if serving.done():
                    await serving
                    raise RuntimeError("ASGI 启动失败")
                await asyncio.sleep(0.02)
        async with httpx.AsyncClient(trust_env=False, timeout=25) as client:
            response = await client.post(
                f"http://127.0.0.1:{port}/api/agent/chat",
                auth=(config["username"], config["password"]),
                headers={"traceparent": f"00-{config['traceId']}-1234567890abcdef-01"},
                json={
                    "question": "两次检索 PostgreSQL backup",
                    "kbIds": config["kbIds"],
                },
            )
            assert response.status_code == 200, response.text
            result = response.json()["data"]
            assert result["status"] == "COMPLETED"
            assert len(result["retrievals"]) == 2
            assert all(r["status"] == "READY" for r in result["retrievals"])
            ids = [item["evidenceId"] for item in result["citations"]]
            assert len(ids) >= 2 and len(ids) == len(set(ids))
            assert all(f"[{ref}]" in result["answer"] for ref in ids)
            assert result["traceId"] == config["traceId"]
            assert all(
                headers["traceparent"].startswith(f"00-{config['traceId']}-")
                for headers in fixture.model_headers
            )
            return {
                "status": result["status"],
                "traceId": result["traceId"],
                "retrievalStatuses": [r["status"] for r in result["retrievals"]],
                "requestIds": [r["requestId"] for r in result["retrievals"]],
                "uniqueCitationIds": ids,
                "modelRequests": len(fixture.model_requests),
                "entrypoint": "POST /api/agent/chat over Uvicorn TCP",
                "graph": "langchain.create_agent / LangGraph / AsyncSqliteSaver",
                "note": (
                    "真实 Python 入口→图工具循环→Java→PG/MinIO；"
                    "主模型和向量模型为协议桩，非语义效果评估"
                ),
            }
    finally:
        server.should_exit = True
        await asyncio.wait_for(serving, 5)
        listener.close()


if __name__ == "__main__":
    logging.basicConfig(level=logging.ERROR, stream=sys.stderr)
    print(json.dumps(asyncio.run(run(json.load(sys.stdin))), ensure_ascii=False))
