"""真实 Java 代理 → Python → MCP 慢工具的停止与断线收口验收。先运行 smoke --leave-enabled。"""

import argparse
import asyncio
import json
import time
import uuid
from pathlib import Path

import httpx


async def main(args):
    results = []
    async with httpx.AsyncClient(
        base_url=args.origin,
        auth=("alice", "atlas-fixture-password"),
        trust_env=False,
        timeout=30,
    ) as http:
        for mode in ["explicit-stop", "disconnect"]:
            c = (
                await http.post(
                    "/api/conversations",
                    json={"requestId": str(uuid.uuid4()), "kbIds": []},
                )
            ).json()["data"]
            run_id = None
            started = time.monotonic()
            async with http.stream(
                "POST",
                "/api/agent/chat/stream",
                headers={"X-Agent-Stream-Version": "3"},
                json={
                    "question": "用慢只读工具查询服务窗口",
                    "mode": "agent",
                    "conversationId": c["id"],
                    "requestId": str(uuid.uuid4()),
                    "messageId": str(uuid.uuid4()),
                },
            ) as response:
                assert response.status_code == 200
                event = None
                async for line in response.aiter_lines():
                    if line.startswith("event:"):
                        event = line[6:].strip()
                    elif line.startswith("data:"):
                        data = json.loads(line[5:])
                        run_id = data["runId"]
                        if event == "tool.start" and data["status"] == "running":
                            if mode == "explicit-stop":
                                stopped = await http.post(
                                    f"/api/conversations/{c['id']}/runs/{run_id}/cancel"
                                )
                                assert stopped.status_code == 200
                            break
                else:
                    raise AssertionError("Slow tool did not enter running state")
            assert time.monotonic() - started < 8, (
                "Start events were buffered until completion"
            )
            async with asyncio.timeout(10):
                while True:
                    state = await http.get(
                        f"/api/conversations/{c['id']}/runs/{run_id}"
                    )
                    state.raise_for_status()
                    saved = state.json()["data"]
                    if saved["run"]["status"] == "CANCELLED":
                        break
                    await asyncio.sleep(0.2)
            audit = (
                await http.get(
                    "/api/tools/calls",
                    auth=("atlas-admin", "atlas-fixture-password"),
                    params={"runId": run_id},
                )
            ).json()["data"]
            assert audit and audit[0]["status"] == "cancelled"
            results.append(
                {
                    "mode": mode,
                    "runId": run_id,
                    "runStatus": "CANCELLED",
                    "toolStatus": "cancelled",
                    "startReceivedBeforeCompletion": True,
                }
            )
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(results, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(results))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--origin", default="http://127.0.0.1:8086")
    parser.add_argument("--output", default="output/tools/cancellation-smoke.json")
    asyncio.run(main(parser.parse_args()))
