import asyncio
import time

from jsonschema import Draft202012Validator

from agent_service.contracts import AgentError
from agent_service.knowledge.adapter import digest, encode
from agent_service.timeline import safe_query
from agent_service.tools.contracts import (
    ExecutionContext,
    ToolCall,
    ToolDefinition,
    ToolResult,
    identity_key,
)


def redact(value, *, depth=0):
    """白名单大小和递归边界；浏览器/日志绝不使用原始 MCP 对象。"""
    if depth > 6:
        return "[已截取]"
    if isinstance(value, dict):
        return {
            str(k)[:80]: "[已隐藏]"
            if any(
                s in str(k).lower()
                for s in (
                    "secret",
                    "token",
                    "password",
                    "authorization",
                    "credential",
                    "api_key",
                    "apikey",
                )
            )
            else redact(v, depth=depth + 1)
            for k, v in list(value.items())[:32]
        }
    if isinstance(value, list):
        return [redact(v, depth=depth + 1) for v in value[:20]]
    if isinstance(value, str):
        return (safe_query(value) or {}).get("query", "")
    return (
        value
        if value is None or isinstance(value, (int, float, bool))
        else "[类型不支持]"
    )


def bounded(value, limit):
    """保持 JSON 结构；绝不截断 JSON 字节或返回无法解析的半个对象。"""

    def cut(item, depth=0):
        return (
            depth > 6
            or isinstance(item, str)
            and len(item) > 1000
            or isinstance(item, list)
            and (len(item) > 20 or any(cut(v, depth + 1) for v in item[:20]))
            or isinstance(item, dict)
            and (
                len(item) > 32
                or any(cut(v, depth + 1) for v in list(item.values())[:32])
            )
        )

    truncated = cut(value)
    value = redact(value)
    if len(encode(value)) <= limit:
        return value, truncated
    if isinstance(value, dict):
        kept = {}
        for key, item in value.items():
            if len(encode({**kept, key: item})) > limit - 64:
                continue
            kept[key] = item
        return {**kept, "truncated": True}, True
    if isinstance(value, list):
        kept = []
        for item in value:
            if len(encode([*kept, item])) > limit - 64:
                break
            kept.append(item)
        return {"items": kept, "truncated": True}, True
    return {"text": str(value)[: max(0, limit // 4 - 64)], "truncated": True}, True


class ToolRegistry:
    def __init__(self):
        self.definitions: dict[str, ToolDefinition] = {}
        self.executors = {}

    def register(self, definition, executor):
        if definition.modelName in self.definitions:
            raise ValueError("模型工具名重复")
        self.definitions[definition.modelName] = definition
        self.executors[definition.modelName] = executor

    def schemas(self):
        return [d.model_schema() for d in self.definitions.values()]


class ToolExecutor:
    def __init__(self, registry, *, concurrency=3):
        self.registry = registry
        self.semaphore = asyncio.Semaphore(concurrency)

    async def execute(self, call: ToolCall, context: ExecutionContext):
        fingerprint = digest(encode([call.modelName, call.arguments]))
        previous = context.calls.get(call.callId)
        if previous:
            if previous[0] != fingerprint:
                raise AgentError(
                    502, "TOOL_CALL_CONFLICT", "调用标识被不同参数重复使用"
                )
            return await asyncio.shield(previous[1])
        definition = self.registry.definitions.get(call.modelName)
        future_calls = max(0, context.max_calls - len(context.calls) - 1)
        limit = min(
            definition.maxResultBytes if definition else 4096,
            context.max_result_bytes
            - context.result_bytes
            - context.reserved_bytes
            - future_calls * 512,
        )
        if limit < 512:
            return ToolResult(
                callId=call.callId, status="unavailable", errorCode="TOOL_RESULT_BUDGET"
            )
        context.reserved_bytes += limit
        task = asyncio.create_task(self._execute(call, context, limit))
        context.calls[call.callId] = (fingerprint, task)
        try:
            return await task
        except asyncio.CancelledError:
            task.cancel()
            await asyncio.gather(task, return_exceptions=True)
            raise

    async def _execute(self, call, context, reserved_limit):
        definition = self.registry.definitions.get(call.modelName)
        if definition is None:
            definition = ToolDefinition(
                toolKey="unknown:" + call.modelName[:120],
                modelName=call.modelName,
                displayName="未知工具",
                description="未授权的调用请求",
                inputSchema={"type": "object"},
            )
        started = time.monotonic()
        result = None
        audit_started = False
        arguments_valid = False

        def model_wire(value):
            agent = getattr(context.services, "agent", None)
            return encode(
                value.modelContent
                if value.sourceType == "knowledge" and agent and agent.mode == "rag"
                else value.model_view()
            )

        agent = getattr(context.services, "agent", None)
        allowance = agent.tool_allowances.get(call.providerCallId) if agent else None

        try:
            # 审计开始与授权是同一个 Java 事务。失败时禁止执行。
            permit = await context.services.control.start(context, definition, call)
            audit_started = True
            if not permit.get("execute", False):
                result = ToolResult.model_validate(permit["result"])
                audit_started = False
                return result
            if reserved_limit <= 512:
                result = ToolResult(
                    callId=call.callId,
                    status="unavailable",
                    errorCode="TOOL_RESULT_BUDGET",
                )
                return result
            async with asyncio.timeout_at(
                min(context.deadline, started + definition.timeoutMs / 1000)
            ):
                async with self.semaphore:
                    if context.cancel.is_set():
                        raise asyncio.CancelledError

                    def contains_identity(value):
                        if isinstance(value, dict):
                            return any(
                                identity_key(key) or contains_identity(child)
                                for key, child in value.items()
                            )
                        return isinstance(value, list) and any(
                            contains_identity(child) for child in value
                        )

                    if definition.kind == "mcp" and contains_identity(call.arguments):
                        raise ValueError(
                            "Model arguments cannot set execution identity"
                        )
                    Draft202012Validator(definition.inputSchema).validate(
                        call.arguments
                    )
                    if len(encode(call.arguments)) > 16384:
                        raise ValueError("argument budget")
                    arguments_valid = True
                    result = await self.registry.executors[call.modelName](
                        definition, call, context
                    )
        except asyncio.CancelledError:
            result = ToolResult(
                callId=call.callId, status="cancelled", errorCode="TOOL_CANCELLED"
            )
            raise
        except TimeoutError:
            result = ToolResult(
                callId=call.callId, status="timeout", errorCode="TOOL_TIMEOUT"
            )
        except AgentError as exc:
            if exc.code == "TOOL_AUDIT_UNAVAILABLE":
                raise
            result = ToolResult(
                callId=call.callId,
                status="denied"
                if exc.status_code in {401, 403, 404, 409}
                else "unavailable",
                errorCode="TOOL_DENIED"
                if exc.status_code in {401, 403, 404, 409}
                else "TOOL_UNAVAILABLE",
            )
        except Exception as exc:
            import httpx
            from jsonschema import ValidationError
            from mcp.shared.exceptions import McpError

            def leaves(error):
                if isinstance(error, BaseExceptionGroup):
                    return [
                        leaf for child in error.exceptions for leaf in leaves(child)
                    ]
                return [error]

            errors = leaves(exc)
            invalid = not arguments_valid and any(
                isinstance(e, (ValidationError, ValueError, TypeError)) for e in errors
            )
            timed_out = any(
                isinstance(e, (TimeoutError, httpx.TimeoutException)) for e in errors
            )
            unavailable = any(
                isinstance(e, (httpx.HTTPError, McpError)) for e in errors
            )
            denied = any(
                isinstance(e, AgentError) and e.status_code in {401, 403, 404, 409}
                for e in errors
            )
            result = ToolResult(
                callId=call.callId,
                status="invalid_arguments"
                if invalid
                else "timeout"
                if timed_out
                else "denied"
                if denied
                else "unavailable"
                if unavailable
                else "failed",
                errorCode="TOOL_INVALID_ARGUMENTS"
                if invalid
                else "TOOL_TIMEOUT"
                if timed_out
                else "TOOL_DENIED"
                if denied
                else "MCP_CONNECTION_UNAVAILABLE"
                if unavailable
                else "TOOL_FAILED",
            )
        finally:
            if result is not None:
                result.durationMs = min(
                    120000, int((time.monotonic() - started) * 1000)
                )
                limit = min(
                    definition.maxResultBytes,
                    reserved_limit,
                    allowance.byte_limit if allowance else definition.maxResultBytes,
                )
                if limit < 512:
                    result = ToolResult(
                        callId=call.callId,
                        status="unavailable",
                        errorCode="TOOL_RESULT_BUDGET",
                        durationMs=result.durationMs,
                    )
                else:
                    # 内部知识正文已经过领域校验，不进行会改变 snapshotHash 的脱敏。
                    if result.sourceType != "knowledge":
                        result.modelContent, cut = bounded(
                            result.modelContent, max(128, limit - 512)
                        )
                        result.truncated |= cut
                    view = (
                        result.modelContent
                        if agent
                        and agent.mode == "rag"
                        and result.sourceType == "knowledge"
                        else result.model_view()
                    )
                    if (
                        len(model_wire(result)) > limit
                        or allowance
                        and not allowance.accepts(view)
                    ):
                        result = ToolResult(
                            callId=call.callId,
                            status="unavailable",
                            errorCode="TOOL_RESULT_BUDGET",
                            durationMs=result.durationMs,
                        )
                context.result_bytes += len(model_wire(result))
                if audit_started:
                    # 结算失败使运行显式失败；数据库到期任务仍会收口 RUNNING 调用。
                    settlement = asyncio.create_task(
                        context.services.control.finish(context, call, result)
                    )
                    try:
                        await asyncio.shield(settlement)
                    except asyncio.CancelledError:
                        await settlement
            context.reserved_bytes -= reserved_limit
        return result
