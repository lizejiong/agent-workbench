import httpx
import pytest
from test_agent import application
from test_react import ScriptedFixture, run, search

from agent_service.contracts import AgentError
from agent_service.state_lock import StateDirectoryLock


@pytest.mark.asyncio
async def test_run_token_budget_stops_before_any_provider_or_tool_request(tmp_path):
    fixture = ScriptedFixture([[search()]])
    app = application(tmp_path, fixture, max_run_tokens=4096)
    async with app.router.lifespan_context(app):
        reply = await run(app)
    assert reply["status"] == "COMPLETED" and not reply["citations"]
    assert not fixture.model_requests and not fixture.tool_calls


@pytest.mark.asyncio
@pytest.mark.parametrize("phase", ["start", "finish"])
async def test_audit_write_failure_is_explicit_and_never_reports_false_success(
    tmp_path, phase
):
    fixture = ScriptedFixture([[search()], []])
    original = fixture.tools

    async def tools(request):
        if (
            phase == "start"
            and request.method == "POST"
            and request.url.path.endswith("/calls")
            or phase == "finish"
            and request.method == "PUT"
        ):
            return httpx.Response(503)
        return await original(request)

    fixture.tools = tools
    app = application(tmp_path, fixture)
    async with app.router.lifespan_context(app):
        with pytest.raises(AgentError) as error:
            await run(app)
    assert error.value.code == "TOOL_AUDIT_UNAVAILABLE"
    assert next(iter(fixture.runs.values()))["status"] == "FAILED"
    assert len(fixture.model_requests) == 1
    if phase == "start":
        assert not any(
            r.url.path == "/internal/rag/retrieve" for r in fixture.java_requests
        )


def test_two_workers_cannot_use_same_state_directory(tmp_path):
    with (
        StateDirectoryLock(tmp_path),
        pytest.raises(RuntimeError, match="单 AI worker"),
    ):
        with StateDirectoryLock(tmp_path):
            pass
