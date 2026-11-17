from agent_service.contracts import AgentError
from agent_service.conversations import ConversationClient
from agent_service.tools.contracts import ToolDefinition
from agent_service.tools.executor import redact


class ToolControl(ConversationClient):
    """只接受受信任的 run + 委托；没有 actorId 参数入口。"""

    async def read(self, method, context, suffix, body=None):
        return await self._read(
            method,
            f"/internal/tools/runs/{context.run_id}{suffix}",
            identity=context.identity,
            body=body,
        )

    async def definitions(self, context):
        rows = await self.read("GET", context, "/tools")
        return [ToolDefinition.model_validate(row) for row in rows]

    async def start(self, context, definition, call):
        try:
            return await self.read(
                "POST",
                context,
                "/calls",
                {
                    "callId": call.callId,
                    "providerCallId": call.providerCallId,
                    "toolKey": definition.toolKey,
                    "configVersion": definition.configVersion,
                    "schemaHash": definition.schemaHash,
                    "batch": call.batch,
                    "order": call.order,
                    "arguments": redact(call.arguments),
                },
            )
        except AgentError as exc:
            if exc.status_code >= 500:
                raise AgentError(
                    503, "TOOL_AUDIT_UNAVAILABLE", "调用审计无法写入，工具未执行"
                ) from None
            raise

    async def authorize(self, context, call):
        return await self.read("POST", context, f"/calls/{call.callId}/authorize")

    async def connection(self, context, call):
        return await self.read("GET", context, f"/calls/{call.callId}/connection")

    async def finish(self, context, call, result):
        try:
            return await self.read(
                "PUT", context, f"/calls/{call.callId}", result.model_dump(mode="json")
            )
        except AgentError:
            raise AgentError(
                503, "TOOL_AUDIT_UNAVAILABLE", "调用结算未确认，请查询审计状态"
            ) from None

    async def evidence(self, context, evidence_id):
        # 只接受本轮已落库 evidenceId，Java 按当前文档版本、范围和 ACL 回表。
        return await self.read(
            "POST", context, "/evidence", {"evidenceId": evidence_id}
        )

    async def validate(self, context, source_ids):
        return await self.read("POST", context, "/validate", {"sourceIds": source_ids})
