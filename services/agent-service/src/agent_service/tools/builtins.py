import asyncio
from types import SimpleNamespace

from agent_service.contracts import AgentError
from agent_service.knowledge.adapter import digest
from agent_service.tools.contracts import ExecutionContext, ToolCall, ToolResult
from agent_service.tools.control import ToolControl
from agent_service.tools.executor import ToolExecutor, ToolRegistry


def platform_id(context, provider_id):
    return context.call_ids.setdefault(
        provider_id, "T" + digest(context.run_id + ":" + provider_id)[:32]
    )


async def knowledge(definition, call, execution):
    from agent_service.graph import perform_search

    context = execution.services.agent
    result = await perform_search(context, call.callId, call.arguments["query"])
    status = {
        "READY": "success",
        "NO_EVIDENCE": "empty",
        "NEEDS_CLARIFICATION": "empty",
        "UNAVAILABLE": "unavailable",
    }[result["status"]]
    return ToolResult(
        callId=call.callId,
        status=status,
        modelContent=result,
        summary="知识检索：" + result["status"],
        sourceType="knowledge",
        evidenceRefs=[e["evidenceId"] for e in result["evidence"]],
        errorCode=result.get("errorCode"),
    )


async def evidence_read(definition, call, execution):
    context = execution.services.agent
    evidence_id = call.arguments["evidenceId"]
    if evidence_id not in context.references.sources:
        return ToolResult(
            callId=call.callId, status="denied", errorCode="EVIDENCE_NOT_IN_RUN"
        )
    row = await context.control.evidence(execution, evidence_id)
    view = {
        k: row[k] for k in ("evidenceId", "content", "documentName", "sourceMetadata")
    }
    context.tool_evidence[call.callId] = [evidence_id]
    return ToolResult(
        callId=call.callId,
        status="success",
        sourceType="knowledge",
        modelContent={"evidence": [view]},
        evidenceRefs=[evidence_id],
        summary="已按当前权限读取证据",
    )


async def prepare_tools(context, mcp):
    context.control = ToolControl(context.gateway)
    context.mcp = mcp
    context.execution = ExecutionContext(
        context.identity,
        context.run_id,
        SimpleNamespace(agent=context, control=context.control),
        max_result_bytes=context.settings.max_result_bytes,
        max_calls=context.settings.max_tool_calls,
    )
    definitions = await context.control.definitions(context.execution)
    registry = ToolRegistry()
    for definition in definitions:
        if definition.kind == "mcp":
            if context.mode != "agent":
                continue
            executor = mcp.execute
        elif definition.toolKey == "builtin:knowledge_search":
            executor = knowledge
        elif definition.toolKey == "builtin:evidence_read" and context.mode == "agent":
            executor = evidence_read
        else:
            continue
        registry.register(definition, executor)
    context.registry = registry
    context.executor = ToolExecutor(
        registry, concurrency=context.settings.tool_concurrency
    )
    if not registry.definitions:
        raise AgentError(503, "TOOLS_UNAVAILABLE", "当前运行没有已授权的只读工具")


async def invoke_tool(context, name, arguments, provider_id, *, order=0):
    call_id = platform_id(context, provider_id)
    call = ToolCall(
        callId=call_id,
        providerCallId=provider_id,
        modelName=name,
        arguments=arguments,
        batch=context.batch,
        order=order,
    )
    if call_id in context.execution.calls:
        return await context.executor.execute(call, context.execution)
    if call_id not in context.execution.calls:
        context.tool_calls += 1
    context.tools[call_id] = {"toolCallId": call_id, "name": name, "status": "RUNNING"}
    if context.changed:
        await context.changed("tool", context.tools[call_id])
    result = None
    sources_before = set(context.references.sources)
    try:
        if context.tool_calls > context.settings.max_tool_calls:
            result = ToolResult(
                callId=call_id, status="unavailable", errorCode="TOOL_CALL_BUDGET"
            )
        else:
            result = await context.executor.execute(call, context.execution)
    except asyncio.CancelledError:
        result = ToolResult(
            callId=call_id, status="cancelled", errorCode="TOOL_CANCELLED"
        )
        raise
    finally:
        if result is not None:
            for key in (
                set(context.tool_evidence.get(call_id, []))
                - sources_before
                - set(result.evidenceRefs)
            ):
                context.references.sources.pop(key, None)
                context.snapshots.pop(key, None)
                context.public_sources.pop(key, None)
            context.tool_evidence[call_id] = [
                e for e in result.evidenceRefs if e.startswith("K")
            ]
            if result.sourceType == "mcp" and result.status == "success":
                context.external_sources.update(result.evidenceRefs)
            context.tools[call_id] = {
                "toolCallId": call_id,
                "name": name,
                "status": result.status,
                "errorCode": result.errorCode,
                "toolTruncated": result.truncated,
            }
            if name == "search_knowledge" and result.sourceType == "knowledge":
                context.tools[call_id] = {
                    "toolCallId": call_id,
                    "name": name,
                    **context.tool_events[-1],
                }
            context.tool_results[call_id] = result
            if context.changed:
                await context.changed("tool", context.tools[call_id])
    return result
