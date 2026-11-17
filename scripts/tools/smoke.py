"""配置 → 发现 → 明确发布/授权 → Agent SSE → 历史/审计 → 禁用/撤权隐藏。"""

import argparse
import asyncio
import json
import os
import uuid
from pathlib import Path

import httpx


async def smoke(args):
    password = os.environ.get("ATLAS_SMOKE_PASSWORD", "atlas-fixture-password")
    admin_password = os.environ.get("ATLAS_SMOKE_ADMIN_PASSWORD", password)
    async with httpx.AsyncClient(
        base_url=args.origin, trust_env=False, timeout=60
    ) as http:

        async def api(method, path, body=None, admin=False, params=None):
            response = await http.request(
                method,
                path,
                json=body,
                params=params,
                auth=(
                    args.admin if admin else args.user,
                    admin_password if admin else password,
                ),
            )
            if not response.is_success:
                raise RuntimeError(f"{method} {path}: HTTP {response.status_code}")
            envelope = response.json()
            if envelope["code"] != "0":
                raise RuntimeError("Unexpected API result")
            return envelope["data"]

        if args.server_id:
            servers = await api("GET", "/api/tools/servers", admin=True)
            server = next(row for row in servers if row["id"] == args.server_id)
        else:
            server = await api(
                "POST",
                "/api/tools/servers",
                {"name": "MCP read-only smoke", "url": args.mcp_url, "enabled": True},
                admin=True,
            )
        discovered = await api(
            "POST", f"/api/tools/servers/{server['id']}/discover", admin=True
        )
        if not args.server_id:
            assert all(not row["enabled"] for row in discovered)
        selected = [
            row
            for row in discovered
            if row["remote_name"] in {"structured", "lookup", "slow", "business_error"}
        ]
        for row in selected:
            await api(
                "PUT",
                "/api/tools/publish",
                {
                    "schemaHash": row["observed_hash"],
                    "enabled": True,
                    "readOnly": True,
                    "timeoutMs": 15000,
                    "maxResultBytes": 4096,
                },
                admin=True,
                params={"toolKey": row["tool_key"]},
            )
            await api(
                "PUT",
                "/api/tools/grant",
                {"userId": args.user_id, "enabled": True},
                admin=True,
                params={"toolKey": row["tool_key"]},
            )
        conversation = await api(
            "POST", "/api/conversations", {"requestId": str(uuid.uuid4()), "kbIds": []}
        )
        frames = []
        async with http.stream(
            "POST",
            "/api/agent/chat/stream",
            auth=(args.user, password),
            headers={"X-Agent-Stream-Version": "3"},
            json={
                "question": args.question,
                "mode": "agent",
                "conversationId": conversation["id"],
                "requestId": str(uuid.uuid4()),
                "messageId": str(uuid.uuid4()),
            },
        ) as response:
            assert response.status_code == 200, "Chat stream could not start"
            event = None
            async for line in response.aiter_lines():
                if line.startswith("event:"):
                    event = line[6:].strip()
                elif line.startswith("data:"):
                    assert len(line.encode()) < 65536
                    frames.append((event, json.loads(line[5:])))
        assert frames[-1][0] == "done" and frames[-1][1]["status"] == "COMPLETED"
        assert [data["seq"] for _, data in frames] == list(range(1, len(frames) + 1))
        successes = [data for event, data in frames if event == "tool.success"]
        assert (
            len(successes) == args.expected_tools
            and len({item["callId"] for item in successes}) == args.expected_tools
        )
        wire = json.dumps(frames, ensure_ascii=False)
        assert (
            "fixture-sensitive-value" not in wire
            and "fixture-private-token" not in wire
        )
        run_id = frames[0][1]["runId"]
        history = await api("GET", f"/api/conversations/{conversation['id']}/messages")
        answer = history["records"][-1]
        assert answer["status"] == "COMPLETED" and not answer["sourcesRestricted"]
        cards = [block for block in answer["blocks"] if block["kind"] == "tool"]
        assert len(cards) == args.expected_tools and all(
            block["sourceType"] == "mcp" for block in cards
        )
        assert all(block["durationMs"] >= 0 for block in cards)
        audit = await api(
            "GET", "/api/tools/calls", admin=True, params={"runId": run_id}
        )
        assert len(audit) == args.expected_tools and all(
            row["status"] == "success" for row in audit
        )
        if not args.leave_enabled:
            for row in selected:
                await api(
                    "PUT",
                    "/api/tools/publish",
                    {
                        "schemaHash": row["observed_hash"],
                        "enabled": False,
                        "readOnly": True,
                        "timeoutMs": 15000,
                        "maxResultBytes": 4096,
                    },
                    admin=True,
                    params={"toolKey": row["tool_key"]},
                )
            hidden = (
                await api("GET", f"/api/conversations/{conversation['id']}/messages")
            )["records"][-1]
            assert (
                hidden["sourcesRestricted"]
                and not hidden["blocks"]
                and not hidden["tools"]
            )
            assert "09:00" not in hidden["content"]
        report = {
            "modelKind": "scripted-http-fixture"
            if args.fixture
            else "configured-model",
            "conversationId": conversation["id"],
            "runId": run_id,
            "serverId": server["id"],
            "discovered": len(discovered),
            "toolSuccesses": len(successes),
            "events": len(frames),
            "continuousSeq": True,
            "historyReplay": True,
            "auditRows": len(audit),
            "secretRedaction": True,
            "disabledAndHidden": not args.leave_enabled,
        }
        output = Path(args.output)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(
            json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
        print(json.dumps(report, ensure_ascii=False))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--origin", default="http://127.0.0.1:8086")
    parser.add_argument("--mcp-url", default="http://127.0.0.1:8765/mcp")
    parser.add_argument(
        "--server-id", help="复用上次 smoke 创建的服务，避免重复发布同名测试能力"
    )
    parser.add_argument("--user", default="alice")
    parser.add_argument("--user-id", default="fixture-alice")
    parser.add_argument("--admin", default="atlas-admin")
    parser.add_argument("--fixture", action="store_true")
    parser.add_argument("--question", default="请用双工具查询服务窗口")
    parser.add_argument("--expected-tools", type=int, default=2)
    parser.add_argument("--leave-enabled", action="store_true")
    parser.add_argument("--output", default="output/tools/smoke.json")
    asyncio.run(smoke(parser.parse_args()))
