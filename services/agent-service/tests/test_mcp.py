"""官方 SDK fixture + 真实本机 HTTP，覆盖客户端生命周期与执行器的失败边界。"""

import asyncio
import hashlib
import runpy
import socket
import time
import uuid
from datetime import UTC, datetime, timedelta
from pathlib import Path
from types import SimpleNamespace

import pytest
import uvicorn

from agent_service.contracts import AgentError
from agent_service.knowledge.adapter import TrustedContext, encode
from agent_service.tools.contracts import ExecutionContext, ToolCall, ToolDefinition
from agent_service.tools.executor import ToolExecutor, ToolRegistry
from agent_service.tools.mcp import MCPManager

FIXTURE_PATH = str(Path(__file__).resolve().parents[3] / "scripts/tools/mcp_fixture.py")


@pytest.fixture
async def fixture_url():
    module = runpy.run_path(FIXTURE_PATH)
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        port = listener.getsockname()[1]
    fixture = module["create_fixture"](port)
    server = uvicorn.Server(
        uvicorn.Config(
            fixture.streamable_http_app(),
            host="127.0.0.1",
            port=port,
            access_log=False,
            log_level="critical",
            timeout_graceful_shutdown=1,
        )
    )
    serving = asyncio.create_task(server.serve())
    try:
        async with asyncio.timeout(5):
            while not server.started:  # noqa: ASYNC110
                if serving.done():
                    await serving
                await asyncio.sleep(0.01)
        yield f"http://127.0.0.1:{port}/mcp"
    finally:
        server.should_exit = True
        await asyncio.wait_for(serving, 5)


class Control:
    def __init__(self, connection):
        self.config = connection
        self.finished = []
        self.started = 0
        self.authorized = asyncio.Event()

    async def start(self, *_):
        self.started += 1
        return {"execute": True}

    async def connection(self, *_):
        return self.config

    async def authorize(self, *_):
        self.authorized.set()
        return {"allowed": True}

    async def finish(self, context, call, result):
        self.finished.append(result)


def execution(connection):
    control = Control(connection)
    identity = TrustedContext(
        "tenant-a",
        "alice",
        str(uuid.uuid4()),
        (),
        None,
        "not-model-visible",
        datetime.now(UTC) + timedelta(minutes=5),
        time.monotonic() + 20,
        "a" * 32,
    )
    return ExecutionContext(
        identity, str(uuid.uuid4()), SimpleNamespace(control=control)
    )


async def setup(url):
    connection = {
        "serverId": str(uuid.uuid4()),
        "url": url,
        "configVersion": 1,
        "credentialRef": None,
    }
    manager = MCPManager([url.removesuffix("/mcp")])
    rows = await manager.discover(connection)
    definitions = [
        ToolDefinition.model_validate(
            {k: v for k, v in row.items() if k != "readOnlyHint"}
        )
        for row in rows
    ]
    registry = ToolRegistry()
    for definition in definitions:
        registry.register(definition, manager.execute)
    return manager, connection, definitions, ToolExecutor(registry)


def call(definitions, remote_name, arguments):
    definition = next(d for d in definitions if d.remoteToolName == remote_name)
    return ToolCall(
        callId="T" + uuid.uuid4().hex,
        providerCallId="vendor-" + uuid.uuid4().hex,
        modelName=definition.modelName,
        arguments=arguments,
    )


@pytest.mark.asyncio
async def test_real_sdk_structured_large_business_error_and_unsupported(fixture_url):
    manager, connection, definitions, executor = await setup(fixture_url)
    context = execution(connection)
    try:
        structured = await executor.execute(
            call(definitions, "structured", {"query": "营业时间"}), context
        )
        assert structured.status == "success" and structured.sourceType == "mcp"
        wire = encode(structured.model_view())
        assert (
            b"fixture-sensitive-value" not in wire
            and b"fixture-private-token" not in wire
        )
        assert structured.evidenceRefs[0].startswith("M")
        large = await executor.execute(
            call(definitions, "large", {"count": 2000}), context
        )
        assert large.status == "success" and large.truncated
        assert len(encode(large.model_view())) <= 4096
        error = await executor.execute(call(definitions, "business_error", {}), context)
        assert error.status == "failed" and error.errorCode == "MCP_BUSINESS_ERROR"
        unsupported = await executor.execute(
            call(definitions, "unsupported", {}), context
        )
        assert unsupported.errorCode == "MCP_CONTENT_UNSUPPORTED"
        assert len(context.services.control.finished) == 4
        assert not manager.active
    finally:
        await manager.aclose()


@pytest.mark.asyncio
async def test_connection_failure_is_not_business_failure_or_empty(fixture_url):
    manager, connection, definitions, executor = await setup(fixture_url)
    context = execution({**connection, "url": fixture_url + "/unavailable"})
    try:
        result = await executor.execute(
            call(definitions, "lookup", {"query": "x"}), context
        )
        assert result.status == "unavailable"
        assert result.errorCode == "MCP_CONNECTION_UNAVAILABLE"
        assert context.services.control.finished[-1].status == "unavailable"
    finally:
        await manager.aclose()


@pytest.mark.asyncio
async def test_permissive_remote_schema_still_cannot_accept_identity(fixture_url):
    manager, connection, definitions, executor = await setup(fixture_url)
    definition = next(d for d in definitions if d.remoteToolName == "lookup")
    executor.registry.definitions[definition.modelName] = definition.model_copy(
        update={"inputSchema": {"type": "object", "additionalProperties": True}}
    )
    context = execution(connection)
    try:
        result = await executor.execute(
            call(
                definitions,
                "lookup",
                {"query": "x", "nested": {"actorId": "other-user"}},
            ),
            context,
        )
        assert result.status == "invalid_arguments"
        assert not context.services.control.authorized.is_set()
    finally:
        await manager.aclose()


@pytest.mark.asyncio
async def test_sdk_discovery_paginates_and_rejects_repeated_cursors():
    from mcp.types import ListToolsResult, Tool

    class Pages:
        def __init__(self, bad=False):
            self.cursors = []
            self.bad = bad

        async def list_tools(self, cursor=None):
            self.cursors.append(cursor)
            name = "second" if cursor else "first"
            return ListToolsResult(
                tools=[Tool(name=name, inputSchema={"type": "object"})],
                nextCursor="next" if cursor is None or self.bad else None,
            )

    manager = MCPManager()
    pages = Pages()
    assert [tool.name for tool in await manager.tools(pages)] == ["first", "second"]
    assert pages.cursors == [None, "next"]
    with pytest.raises(ValueError, match="MCP_CURSOR_INVALID"):
        await manager.tools(Pages(bad=True))
    await manager.aclose()


@pytest.mark.asyncio
async def test_same_remote_names_have_stable_collision_free_mappings(fixture_url):
    manager, connection, definitions, _ = await setup(fixture_url)
    try:
        other = await manager.discover({**connection, "serverId": str(uuid.uuid4())})
        assert {d.modelName for d in definitions}.isdisjoint(
            {d["modelName"] for d in other}
        )
        assert all(len(d.modelName) <= 64 for d in definitions)
        again = await manager.discover(connection)
        assert [d.modelName for d in definitions] == [d["modelName"] for d in again]
    finally:
        await manager.aclose()


@pytest.mark.asyncio
async def test_dynamic_schema_and_remote_drift_fail_closed(fixture_url):
    manager, connection, definitions, executor = await setup(fixture_url)
    context = execution(connection)
    try:
        invalid = await executor.execute(
            call(definitions, "slow", {"seconds": "not-number"}), context
        )
        assert (
            invalid.status == "invalid_arguments"
            and not context.services.control.authorized.is_set()
        )
        definition = next(d for d in definitions if d.remoteToolName == "lookup")
        executor.registry.definitions[definition.modelName] = definition.model_copy(
            update={"schemaHash": "0" * 64}
        )
        changed = await executor.execute(
            call(definitions, "lookup", {"query": "x"}), context
        )
        assert changed.errorCode == "MCP_SCHEMA_CHANGED" and changed.status == "denied"
        assert not context.services.control.authorized.is_set()
    finally:
        await manager.aclose()


@pytest.mark.asyncio
async def test_timeout_cancellation_and_no_duplicate_execution(fixture_url):
    manager, connection, definitions, executor = await setup(fixture_url)
    context = execution(connection)
    try:
        definition = next(d for d in definitions if d.remoteToolName == "slow")
        executor.registry.definitions[definition.modelName] = definition.model_copy(
            update={"timeoutMs": 300}
        )
        slow_call = call(definitions, "slow", {"seconds": 2})
        timed_out = await executor.execute(slow_call, context)
        assert timed_out.status == "timeout"
        assert await executor.execute(slow_call, context) is timed_out
        assert context.services.control.started == 1
        executor.registry.definitions[definition.modelName] = definition
        context.services.control.authorized.clear()
        task = asyncio.create_task(
            executor.execute(call(definitions, "slow", {"seconds": 5}), context)
        )
        await asyncio.wait_for(context.services.control.authorized.wait(), 3)
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        assert context.services.control.finished[-1].status == "cancelled"
        assert not manager.active
    finally:
        await manager.aclose()


@pytest.mark.asyncio
async def test_endpoint_and_credential_isolation_without_secret_in_output(
    fixture_url, monkeypatch
):
    manager, connection, definitions, executor = await setup(fixture_url)
    monkeypatch.setenv("ATLAS_FIXTURE_USER_A", "fixture-secret-a")
    monkeypatch.setenv("ATLAS_FIXTURE_USER_B", "fixture-secret-b")
    try:
        a = {
            **connection,
            "credentialRef": "env:ATLAS_FIXTURE_USER_A",
            "credentialScope": "tenant:alice:run1",
        }
        b = {
            **connection,
            "credentialRef": "env:ATLAS_FIXTURE_USER_B",
            "credentialScope": "tenant:bob:run2",
        }
        assert manager.credentials(a) != manager.credentials(b)
        results = await asyncio.gather(
            *[
                executor.execute(
                    call(definitions, "principal_hash", {}), execution(config)
                )
                for config in [a, b]
            ]
        )
        assert all(result.status == "success" for result in results)
        assert [r.modelContent["content"]["principalHash"] for r in results] == [
            hashlib.sha256(("Bearer " + v).encode()).hexdigest()
            for v in ["fixture-secret-a", "fixture-secret-b"]
        ]
        assert b"fixture-secret" not in encode([r.model_view() for r in results])
        assert not manager.active
        with pytest.raises(AgentError):
            manager.credentials({**connection, "url": "http://127.0.0.1:1/internal"})
    finally:
        await manager.aclose()
