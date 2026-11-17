"""显式开发环境：真实 Java/Python/MCP，模型为可脚本化 HTTP stub。"""

import argparse
import asyncio
import hashlib
import json
import os
import re
import secrets
import socket
import subprocess
import uuid
from dataclasses import replace
from pathlib import Path

import httpx
import uvicorn
from agent_service.app import create_app
from agent_service.config import Settings
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse, StreamingResponse
from mcp_fixture import create_fixture

ROOT = Path(__file__).resolve().parents[2]
PASSWORD = "atlas-fixture-password"


def model_stub():
    app = FastAPI()
    app.state.calls = 0

    @app.post("/v1/chat/completions")
    async def completion(request: Request):
        body = await request.json()
        app.state.calls += 1
        messages = body["messages"]
        question = next(
            (
                m["content"]
                for m in reversed(messages)
                if m["role"] == "user"
                and not m["content"].startswith("工具阶段已经结束")
            ),
            "",
        )
        results = []
        for m in messages:
            if m["role"] == "tool":
                try:
                    results.append(json.loads(m["content"]))
                except ValueError:
                    results.append({"status": "failed", "evidence": []})
        calls = []
        if body.get("tools") and not results:
            functions = [t["function"] for t in body["tools"]]
            wanted = (
                "慢只读"
                if "慢" in question
                else "业务错误"
                if "业务错误" in question
                else "结构化测试结果"
            )
            selected = [f for f in functions if wanted in f["description"]]
            if "双工具" in question:
                selected += [f for f in functions if "只读测试资料" in f["description"]]
            if not selected:
                selected = [f for f in functions if f["name"] == "search_knowledge"]
            for function in selected[:2]:
                arguments = (
                    {"seconds": 10}
                    if wanted == "慢只读"
                    else {}
                    if wanted == "业务错误"
                    else {"query": question}
                )
                if function["name"] == "search_knowledge":
                    arguments = {"query": question}
                calls.append(
                    {
                        "id": "fixture-vendor-" + uuid.uuid4().hex,
                        "type": "function",
                        "function": {
                            "name": function["name"],
                            "arguments": json.dumps(arguments),
                        },
                    }
                )
        refs = list(
            dict.fromkeys(
                e
                for result in results
                for e in result.get(
                    "evidenceRefs",
                    [e["evidenceId"] for e in result.get("evidence", [])],
                )
            )
        )
        text = (
            (
                "本轮只读资料显示服务窗口为 09:00–18:00。"
                + "".join(f"[{e}]" for e in refs)
            )
            if refs
            else "当前没有取得有效资料，无法确认企业事实。"
        )
        message = {
            "role": "assistant",
            "content": None if calls else text,
            **({"tool_calls": calls} if calls else {}),
        }
        finish = "tool_calls" if calls else "stop"
        if body.get("stream"):

            async def frames():
                def frame(delta, reason=None):
                    return (
                        "data: "
                        + json.dumps(
                            {
                                "id": "fixture-stream",
                                "object": "chat.completion.chunk",
                                "created": 1,
                                "model": "atlas-http-fixture",
                                "choices": [
                                    {
                                        "index": 0,
                                        "delta": delta,
                                        "finish_reason": reason,
                                    }
                                ],
                            },
                            ensure_ascii=False,
                        )
                        + "\n\n"
                    )

                if calls:
                    yield frame(
                        {
                            "role": "assistant",
                            "tool_calls": [
                                {"index": index, **call}
                                for index, call in enumerate(calls)
                            ],
                        }
                    )
                else:
                    for offset in range(0, len(text), 8):
                        yield frame({"content": text[offset : offset + 8]})
                        await asyncio.sleep(0.03)
                yield frame({}, finish)
                yield "data: [DONE]\n\n"

            return StreamingResponse(frames(), media_type="text/event-stream")
        return JSONResponse(
            {
                "id": "fixture-planning",
                "object": "chat.completion",
                "created": 1,
                "model": "atlas-http-fixture",
                "choices": [{"index": 0, "message": message, "finish_reason": finish}],
            }
        )

    @app.get("/health")
    async def health():
        return {"fixture": True, "calls": app.state.calls}

    return app


async def main(args):
    if os.getenv("ATLAS_TOOL_FIXTURE") != "true":
        raise SystemExit(
            "Set ATLAS_TOOL_FIXTURE=true. This stack is for development/test only."
        )
    state_dir = ROOT / args.state_dir
    state_dir.mkdir(parents=True, exist_ok=True)
    jar = (ROOT / args.backend_jar).resolve()
    if not jar.is_file():
        raise SystemExit(
            "Backend jar missing. Build with mvnw -pl services/backend -am package -DskipTests."
        )
    for port in [args.backend_port, args.agent_port, args.mcp_port, args.model_port]:
        with socket.socket() as check:
            try:
                check.bind(("127.0.0.1", port))
            except OSError:
                raise RuntimeError(
                    f"Development port {port} is occupied; stop the previous stack first."
                ) from None
    # Windows 的 javapath 可能是会再启动子进程的 shim；直接使用实际 JDK，确保可回收。
    properties = await asyncio.to_thread(
        subprocess.run,
        [args.java, "-XshowSettings:properties", "-version"],
        capture_output=True,
        text=True,
        check=True,
        creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0,
    )
    home = re.search(r"^\s*java.home = (.+)$", properties.stderr, re.MULTILINE)
    java_binary = (
        str(Path(home[1].strip()) / "bin" / ("java.exe" if os.name == "nt" else "java"))
        if home
        else args.java
    )
    inbound, outbound = secrets.token_urlsafe(32), secrets.token_urlsafe(32)
    backend = f"http://127.0.0.1:{args.backend_port}"
    agent_origin = f"http://127.0.0.1:{args.agent_port}"
    mcp_origin = f"http://127.0.0.1:{args.mcp_port}"
    config = {
        "server": {"port": args.backend_port},
        "spring": {
            "mvc": {"async": {"request-timeout": "145s"}},
            "datasource": {
                "url": args.db_url,
                "username": args.db_user,
                "password": os.getenv("ATLAS_TEST_DB_PASSWORD", ""),
            },
        },
        "kb": {
            "worker": {"enabled": False},
            "storage": {"enabled": False},
            "security": {
                "accounts": [
                    {
                        "username": user,
                        "password": PASSWORD,
                        "tenantId": "fixture-tenant",
                        "userId": "fixture-" + user,
                        "operator": user == "atlas-admin",
                    }
                    for user in ["alice", "bob", "atlas-admin"]
                ]
            },
            "rag": {
                "internal": {
                    "enabled": True,
                    "serviceTokenHashes": {
                        "agent-fixture": hashlib.sha256(outbound.encode()).hexdigest()
                    },
                }
            },
            "tools": {
                "agentOrigin": agent_origin,
                "agentToken": inbound,
                "allowedOrigins": [mcp_origin],
            },
        },
    }
    environment = {**os.environ, "SPRING_APPLICATION_JSON": json.dumps(config)}
    java_log = (state_dir / "backend.log").open("w", encoding="utf-8")
    java = await asyncio.to_thread(
        subprocess.Popen,
        [java_binary, "-Xmx768m", "-jar", str(jar)],
        cwd=ROOT,
        env=environment,
        stdout=java_log,
        stderr=subprocess.STDOUT,
        creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0,
    )
    settings = Settings(
        java_origin=backend,
        service_id="agent-fixture",
        service_token=outbound,
        model_base_url=f"http://127.0.0.1:{args.model_port}/v1",
        model="atlas-http-fixture",
        model_api_key="development-http-stub",
        state_dir=state_dir / "agent",
        inbound_token=inbound,
        mcp_allowed_origins=(mcp_origin,),
        summary_enabled=False,
        turn_timeout=40,
    )
    model_kind = "scripted-http-fixture"
    if args.real_model_env:
        source = json.loads(Path(args.real_model_env).read_text(encoding="utf-8"))
        source = source.get("agent", source)
        settings = replace(
            settings,
            model_base_url=source["AGENT_MODEL_BASE_URL"],
            model=source["AGENT_MODEL"],
            model_api_key=source["AGENT_MODEL_API_KEY"],
            max_model_calls=4,
            max_tool_calls=2,
            max_output_tokens=512,
            turn_timeout=90,
            max_run_tokens=131072,
        )
        model_kind = "configured-provider"
    servers = [
        uvicorn.Server(
            uvicorn.Config(
                app,
                host="127.0.0.1",
                port=port,
                access_log=False,
                log_level="warning",
                timeout_graceful_shutdown=3,
            )
        )
        for app, port in [
            (create_fixture(args.mcp_port).streamable_http_app(), args.mcp_port),
            (model_stub(), args.model_port),
            (create_app(settings), args.agent_port),
        ]
    ]
    tasks = [asyncio.create_task(server.serve()) for server in servers]
    (state_dir / "ready.json").write_text(
        json.dumps(
            {
                "backendOrigin": backend,
                "agentOrigin": agent_origin,
                "mcpUrl": mcp_origin + "/mcp",
                "username": "alice",
                "adminUsername": "atlas-admin",
                "password": PASSWORD,
                "modelKind": model_kind,
            }
        ),
        encoding="utf-8",
    )
    try:
        async with httpx.AsyncClient(trust_env=False) as http:
            async with asyncio.timeout(70):
                while True:
                    if java.poll() is not None:
                        raise RuntimeError(
                            "Backend exited; inspect the development backend.log."
                        )
                    try:
                        response = await http.get(
                            backend + "/actuator/health", timeout=1
                        )
                        if response.status_code == 200 and all(
                            s.started for s in servers
                        ):
                            break
                    except httpx.HTTPError:
                        pass
                    await asyncio.sleep(0.2)
        print(
            "TOOLS_DEV_READY "
            + json.dumps(
                {
                    "backendOrigin": backend,
                    "mcpUrl": mcp_origin + "/mcp",
                    "stateDir": str(state_dir),
                    "modelKind": model_kind,
                }
            ),
            flush=True,
        )
        stop_file = state_dir / "stop"
        while not await asyncio.to_thread(stop_file.exists):
            if java.poll() is not None:
                raise RuntimeError("Development backend exited; inspect backend.log.")
            if any(task.done() for task in tasks):
                await asyncio.gather(*tasks)
                break
            await asyncio.sleep(0.5)
    finally:
        for server in servers:
            server.should_exit = True
        await asyncio.gather(*tasks, return_exceptions=True)
        java.terminate()
        try:
            await asyncio.to_thread(java.wait, timeout=8)
        except subprocess.TimeoutExpired:
            java.kill()
            await asyncio.to_thread(java.wait)
        java_log.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--db-url", default="jdbc:postgresql://127.0.0.1:55468/atlas_tools"
    )
    parser.add_argument("--db-user", default="atlas_test")
    parser.add_argument("--java", default="java")
    parser.add_argument(
        "--backend-jar",
        default="services/backend/target/rag-backend-0.1.0-SNAPSHOT.jar",
    )
    parser.add_argument("--state-dir", default=".cache/tools-dev-stack")
    parser.add_argument(
        "--real-model-env",
        help="可选服务端 JSON 环境配置文件，只读取 AGENT_MODEL_*；不输出凭据",
    )
    for name, default in [
        ("backend", 8086),
        ("agent", 8096),
        ("mcp", 8765),
        ("model", 8766),
    ]:
        parser.add_argument(f"--{name}-port", type=int, default=default)
    asyncio.run(main(parser.parse_args()))
