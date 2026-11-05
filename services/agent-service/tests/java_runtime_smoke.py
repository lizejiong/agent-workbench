"""Java 集成验收通过 stdin 启动真实 ASGI 入口；只有模型使用协议桩。"""

import asyncio
import json
import logging
import socket
import sys
import uuid
from pathlib import Path

import httpx
import uvicorn
from protocol_fixture import ProtocolFixture

from agent_service.app import create_app
from agent_service.config import Settings


async def run(config):
    fixture = ProtocolFixture()
    fixture.answer_delay = 0.035
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
            baseline_calls = len(fixture.model_requests)
            java = config["origin"]
            auth = (config["username"], config["password"])
            created = await client.post(
                java + "/api/conversations",
                auth=auth,
                json={"requestId": str(uuid.uuid4()), "kbIds": config["kbIds"]},
            )
            assert created.status_code == 201, created.text
            conversation = created.json()["data"]["id"]
            body = {
                "conversationId": conversation,
                "requestId": str(uuid.uuid4()),
                "messageId": str(uuid.uuid4()),
                "question": "两次检索 PostgreSQL backup",
            }

            async def streaming(payload, cancel=False):
                events = []
                kind = None
                checked_running = checked_partial = False
                async with client.stream(
                    "POST",
                    f"http://127.0.0.1:{port}/api/agent/chat/stream",
                    auth=auth,
                    json=payload,
                ) as stream:
                    assert stream.status_code == 200, await stream.aread()
                    async for line in stream.aiter_lines():
                        if line.startswith("event:"):
                            kind = line[6:].strip()
                        elif line.startswith("data:"):
                            data = json.loads(line[5:])
                            events.append((kind, data))
                            if kind == "delta":
                                state = await client.get(
                                    java
                                    + f"/api/conversations/{conversation}/messages",
                                    auth=auth,
                                )
                                last = state.json()["data"]["records"][-1]
                                checked_running |= last["status"] == "RUNNING"
                                checked_partial |= (
                                    bool(last["content"])
                                    and last["status"] == "RUNNING"
                                )
                                if cancel:
                                    cancel_path = f"/api/conversations/{conversation}"
                                    cancel_path += f"/runs/{last['runId']}/cancel"
                                    response = await client.post(
                                        java + cancel_path,
                                        auth=auth,
                                    )
                                    assert response.status_code == 200
                                    cancel = False
                return events, checked_running, checked_partial

            events, running, partial = await streaming(body)
            assert events[0][0] == "meta" and events[-1][0] == "done", events
            assert events[-1][1]["status"] == "COMPLETED" and running and partial
            before_repeat = len(fixture.model_requests)
            repeated, _, _ = await streaming(body)
            assert (
                repeated[-1][1]["replayed"]
                and len(fixture.model_requests) == before_repeat
            )
            restored = (
                await client.get(
                    java + f"/api/conversations/{conversation}/messages", auth=auth
                )
            ).json()["data"]["records"]
            assert (
                len(restored) == 2
                and restored[-1]["citations"]
                and len(restored[-1]["tools"]) == 2
            )
            assert restored[-1]["evidence"][0]["versions"]["indexes"]
            denied = await client.get(
                java + f"/api/conversations/{conversation}/messages",
                auth=("bob", "bob-local-test-password"),
            )
            assert denied.status_code == 404
            fixture.block_answer = True
            fixture.answer_closed.clear()
            cancelled, _, _ = await streaming(
                {
                    **body,
                    "requestId": str(uuid.uuid4()),
                    "messageId": str(uuid.uuid4()),
                    "question": "继续解释 PostgreSQL backup",
                },
                True,
            )
            assert (
                cancelled[-1][1]["status"] == "CANCELLED"
                and fixture.answer_closed.is_set()
            ), cancelled
            persisted = (
                await client.get(
                    java + f"/api/conversations/{conversation}/messages", auth=auth
                )
            ).json()["data"]["records"]
            assert (
                len(persisted) == 4
                and persisted[-1]["status"] == "CANCELLED"
                and persisted[-1]["content"]
            )
            assert any(
                m["role"] == "assistant"
                for m in fixture.model_requests[before_repeat]["messages"]
            )
            return {
                "status": result["status"],
                "traceId": result["traceId"],
                "retrievalStatuses": [r["status"] for r in result["retrievals"]],
                "requestIds": [r["requestId"] for r in result["retrievals"]],
                "uniqueCitationIds": ids,
                "modelRequests": baseline_calls,
                "totalModelRequests": len(fixture.model_requests),
                "conversationPersistence": {
                    "javaHistory": True,
                    "nativeSse": True,
                    "partialSavedWhileRunning": partial,
                    "duplicateDidNotExecute": True,
                    "crossUserDenied": True,
                    "cancelledPartialSaved": True,
                    "authorizedHistoryReused": True,
                },
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
