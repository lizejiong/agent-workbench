"""生成 Java Agent 会话契约；Python SSE 由 FastAPI + CONVERSATIONS.md 描述。"""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
UUID = {"type": "string", "format": "uuid"}
TEXT = {"type": "string"}
TIME = {"type": "string", "format": "date-time"}
STATES = ["RUNNING", "COMPLETED", "CANCELLED", "FAILED", "TIMED_OUT"]


def ref(name):
    return {"$ref": "#/components/schemas/" + name}


def array(item, **extra):
    return {"type": "array", "items": item, **extra}


def obj(properties, required=()):
    return {"type": "object", "properties": properties, "required": list(required), "additionalProperties": False}


scope = {"kbIds": array(UUID, maxItems=64, nullable=True), "documentIds": array(UUID, minItems=1, maxItems=500, nullable=True)}
schemas = {
    "Create": obj({"requestId": UUID, "title": {**TEXT, "maxLength": 120}, **scope}, ["requestId"]),
    "Rename": obj({"title": {**TEXT, "minLength": 1, "maxLength": 120}}, ["title"]),
    "Delegate": obj({"serviceId": {**TEXT, "pattern": "^[a-z0-9-]{1,80}$"}}, ["serviceId"]),
    "Begin": obj({"requestId": UUID, "messageId": UUID, "question": {**TEXT, "minLength": 1, "maxLength": 4000}, "deadline": TIME}, ["requestId", "messageId", "question", "deadline"]),
    "Snapshot": obj({"evidenceId": {**TEXT, "pattern": "^K[a-f0-9]{32}_E[1-9][0-9]{0,3}$"}, "kbId": UUID, "documentId": UUID, "chunkId": UUID,
                     "generation": {"type": "integer", "minimum": 1}, "content": {**TEXT, "minLength": 1, "maxLength": 16384},
                     "sourceContentHash": {**TEXT, "pattern": "^[a-f0-9]{64}$"}, "snapshotHash": {**TEXT, "pattern": "^[a-f0-9]{64}$"},
                     "sourceMetadata": {"type": "object", "maxProperties": 64, "nullable": True}, "versions": {"type": "object", "maxProperties": 64, "nullable": True},
                     "truncated": {"type": "boolean"}, "toolTruncated": {"type": "boolean"}},
                    ["evidenceId", "kbId", "documentId", "chunkId", "generation", "content", "sourceContentHash", "snapshotHash"]),
    "Tool": obj({"toolCallId": {**TEXT, "maxLength": 128}, "name": {**TEXT, "enum": ["search_knowledge"]}, "status": {**TEXT, "enum": ["RUNNING", "READY", "NO_EVIDENCE", "NEEDS_CLARIFICATION", "UNAVAILABLE"]},
                 "requestId": {**UUID, "nullable": True}, "traceId": {**TEXT, "maxLength": 32, "nullable": True}, "strategy": {**TEXT, "maxLength": 32, "nullable": True},
                 "degraded": {"type": "boolean"}, "toolTruncated": {"type": "boolean"}, "errorCode": {**TEXT, "nullable": True, "pattern": "^[A-Z0-9_]{1,80}$"}, "retryable": {"type": "boolean"}}, ["toolCallId", "name", "status"]),
    "Checkpoint": obj({"sequence": {"type": "integer", "minimum": 1}, "status": {**TEXT, "enum": STATES}, "content": {**TEXT, "maxLength": 65536},
                       "evidence": array(ref("Snapshot"), maxItems=128), "citations": array(TEXT, maxItems=128), "tools": array(ref("Tool"), maxItems=32),
                       "model": {**TEXT, "maxLength": 120, "nullable": True}, "promptVersion": {**TEXT, "maxLength": 120, "nullable": True},
                       "promptHash": {**TEXT, "pattern": "^[a-f0-9]{64}$", "nullable": True}, "errorCode": {**TEXT, "pattern": "^[A-Z0-9_]{1,80}$", "nullable": True}},
                      ["sequence", "status", "content", "evidence", "citations", "tools"]),
    "Result": {"type": "object", "properties": {"code": TEXT, "message": TEXT, "data": {}, "requestId": TEXT}},
}


schemas['ToolArguments'] = obj({'query': {**TEXT, 'minLength': 1, 'maxLength': 1000}}, ['query'])
schemas['Block'] = obj({
    'blockId': {**TEXT, 'pattern': '^B[a-f0-9]{32}$'}, 'kind': {**TEXT, 'enum': ['answer', 'tool', 'hint', 'error']},
    'status': {**TEXT, 'enum': ['streaming', 'pending', 'running', 'done', 'failed', 'interrupted']},
    'text': {**TEXT, 'maxLength': 65536, 'nullable': True}, 'toolCallId': {**TEXT, 'maxLength': 128, 'nullable': True},
    'name': {**TEXT, 'enum': ['search_knowledge'], 'nullable': True}, 'startedAt': {**TIME, 'nullable': True},
    'durationMs': {'type': 'integer', 'minimum': 0, 'maximum': 120000, 'nullable': True},
    'batchId': {**TEXT, 'pattern': '^B[a-f0-9]{32}$', 'nullable': True},
    'callIndex': {'type': 'integer', 'minimum': 0, 'maximum': 31, 'nullable': True},
    'arguments': {**ref('ToolArguments'), 'nullable': True}, 'evidenceIds': array(TEXT, maxItems=128, nullable=True),
    'errorCode': {**TEXT, 'pattern': '^[A-Z0-9_]{1,80}$', 'nullable': True},
}, ['blockId', 'kind', 'status'])
schemas['Checkpoint']['properties'].update({
    'blocks': array(ref('Block'), maxItems=128, nullable=True),
    'eventSeq': {'type': 'integer', 'minimum': 0, 'nullable': True},
})


def operation(name, schema=None, code=200, internal=False):
    value = {"operationId": name, "security": [{"serviceBearer": [], "serviceId": [], "delegation": []}] if internal else [{"basicAuth": []}],
             "responses": {str(code): {"description": "Success", **({"content": {"application/json": {"schema": ref("Result")}}} if code != 204 else {})},
                           "400": {"description": "Invalid input"}, "401": {"description": "Missing or expired authentication/delegation"},
                           "404": {"description": "Not owned, deleted or unavailable"}, "409": {"description": "Idempotency, run state or evidence conflict"}, "503": {"description": "Dependency unavailable"}}}
    if schema:
        value["requestBody"] = {"required": True, "content": {"application/json": {"schema": ref(schema)}}}
    if internal:
        value["parameters"] = [{"in": "header", "name": "X-Rag-Deadline", "required": True, "schema": TIME},
                               {"in": "header", "name": "X-Request-Id", "required": False, "schema": TEXT},
                               {"in": "header", "name": "traceparent", "required": False, "schema": TEXT}]
    return value


paths = {
    "/api/conversations": {"post": operation("createConversation", "Create", 201), "get": operation("listConversations")},
    "/api/conversations/{id}": {"get": operation("getConversation"), "patch": operation("renameConversation", "Rename"), "delete": operation("deleteConversation", code=204)},
    "/api/conversations/{id}/messages": {"get": operation("listConversationMessages")},
    "/api/conversations/{id}/delegations": {"post": operation("delegateConversation", "Delegate", 201)},
    "/api/conversations/{id}/runs/{runId}": {"get": operation("getConversationRun")},
    "/api/conversations/{id}/runs/{runId}/cancel": {"post": operation("cancelConversationRun")},
    "/internal/agent/conversations/{id}/runs": {"post": operation("beginAgentRun", "Begin", internal=True)},
    "/internal/agent/conversations/{id}/history": {"get": operation("getAgentContextHistory", internal=True)},
    "/internal/agent/conversations/{id}/runs/{runId}": {"get": operation("getAgentRun", internal=True), "patch": operation("checkpointAgentRun", "Checkpoint", internal=True)},
}
schemas["Conversation"] = obj({"id": UUID, "title": TEXT, **scope, "createdAt": TIME, "activeAt": TIME, "status": {**TEXT, "enum": ["IDLE", *STATES]}}, ["id", "title", "kbIds", "createdAt", "activeAt", "status"])
schemas["Run"] = obj({"runId": UUID, "conversationId": UUID, "requestId": UUID, "userMessageId": UUID, "assistantMessageId": UUID,
                      "traceId": TEXT, "status": {**TEXT, "enum": STATES}, "checkpointSequence": {"type": "integer", "format": "int64"}, "deadline": TIME,
                      "errorCode": {**TEXT, "nullable": True}, "created": {"type": "boolean"}},
                     ["runId", "conversationId", "requestId", "userMessageId", "assistantMessageId", "traceId", "status", "checkpointSequence", "deadline", "created"])
schemas["VisibleEvidence"] = obj({**schemas["Snapshot"]["properties"], "documentName": TEXT, "fileUrl": TEXT}, ["evidenceId", "kbId", "documentId", "chunkId", "generation", "documentName", "content", "sourceContentHash", "snapshotHash", "fileUrl"])
schemas["Message"] = obj({"id": UUID, "runId": UUID, "requestId": UUID, "sequence": {"type": "integer", "format": "int64"}, "role": {**TEXT, "enum": ["user", "assistant"]},
                          "content": TEXT, "status": {**TEXT, "enum": STATES}, "createdAt": TIME, "updatedAt": TIME, "sourcesRestricted": {"type": "boolean"},
                          "evidence": array(ref("VisibleEvidence")), "citations": array(ref("VisibleEvidence")), "tools": array(ref("Tool")),
                          "model": {**TEXT, "nullable": True}, "promptVersion": {**TEXT, "nullable": True}, "promptHash": {**TEXT, "nullable": True}},
                         ["id", "runId", "requestId", "sequence", "role", "content", "status", "createdAt", "updatedAt", "sourcesRestricted", "evidence", "citations", "tools"])
schemas['VisibleBlock'] = obj({**schemas['Block']['properties'],
    'displayName': TEXT, 'retrievalStatus': {**TEXT, 'nullable': True}, 'degraded': {'type': 'boolean'},
    'strategy': {**TEXT, 'nullable': True}, 'toolTruncated': {'type': 'boolean'},
    'result': obj({'evidence': array(ref('VisibleEvidence'))}, ['evidence']),
}, ['blockId', 'kind', 'status'])
schemas['Message']['properties'].update({'blocks': array(ref('VisibleBlock')), 'eventSeq': {'type': 'integer', 'minimum': 0}})
schemas['Message']['required'].extend(['blocks', 'eventSeq'])
schemas["ConversationPage"] = obj({"current": {"type": "integer"}, "size": {"type": "integer"}, "total": {"type": "integer", "format": "int64"}, "records": array(ref("Conversation"))}, ["current", "size", "total", "records"])
schemas["MessagePage"] = obj({"records": array(ref("Message")), "hasMore": {"type": "boolean"}, "nextBeforeSequence": {"type": "integer", "format": "int64"}}, ["records", "hasMore", "nextBeforeSequence"])
schemas["RunResult"] = obj({"run": ref("Run"), "message": ref("Message")}, ["run", "message"])
schemas["History"] = array(obj({"id": UUID, "runId": UUID, "role": {**TEXT, "enum": ["user", "assistant"]}, "content": TEXT, "evidence": array(ref("VisibleEvidence"))}, ["id", "runId", "role", "content", "evidence"]))
schemas["Delegation"] = obj({"delegationId": UUID, "delegationToken": TEXT, "serviceId": TEXT, "agentSessionId": UUID, "tenantId": TEXT, "userId": TEXT, **scope, "expiresAt": TIME},
                           ["delegationId", "delegationToken", "serviceId", "agentSessionId", "tenantId", "userId", "kbIds", "expiresAt"])
responses = {"createConversation": "Conversation", "listConversations": "ConversationPage", "getConversation": "Conversation", "renameConversation": "Conversation",
             "listConversationMessages": "MessagePage", "delegateConversation": "Delegation", "getConversationRun": "RunResult", "cancelConversationRun": "Run",
             "beginAgentRun": "Run", "getAgentContextHistory": "History", "getAgentRun": "RunResult", "checkpointAgentRun": "Run"}
for path, methods in paths.items():
    for operation_value in methods.values():
        name = responses.get(operation_value["operationId"])
        if name:
            for status, response_value in operation_value["responses"].items():
                if status.startswith("2"):
                    response_value["content"]["application/json"]["schema"] = {"allOf": [ref("Result"), {"type": "object", "properties": {"data": ref(name)}}]}
        for name in ("id", "runId"):
            if "{" + name + "}" in path:
                operation_value.setdefault("parameters", []).append({"in": "path", "name": name, "required": True, "schema": UUID})
paths["/api/conversations"]["get"]["parameters"] = [
    {"in": "query", "name": "current", "schema": {"type": "integer", "minimum": 1, "maximum": 10000, "default": 1}},
    {"in": "query", "name": "size", "schema": {"type": "integer", "minimum": 1, "maximum": 100, "default": 30}},
    {"in": "query", "name": "search", "schema": {**TEXT, "maxLength": 120}},
]
paths["/api/conversations/{id}/messages"]["get"]["parameters"] += [
    {"in": "query", "name": "size", "schema": {"type": "integer", "minimum": 1, "maximum": 100, "default": 30}},
    {"in": "query", "name": "beforeSequence", "schema": {"type": "integer", "format": "int64", "minimum": 1}},
]
document = {"openapi": "3.0.3", "info": {"title": "Java Agent Conversation API", "version": "2026-11-06", "description": "Response fields and Python SSE v1/v2: docs/rag/CONVERSATIONS.md and TIMELINE.md"},
            "paths": paths, "components": {"schemas": schemas, "securitySchemes": {
                "basicAuth": {"type": "http", "scheme": "basic"}, "serviceBearer": {"type": "http", "scheme": "bearer"},
                "serviceId": {"type": "apiKey", "in": "header", "name": "X-Rag-Service"},
                "delegation": {"type": "apiKey", "in": "header", "name": "X-Rag-Delegation"},
            }}}
target = ROOT / "services/backend/src/main/resources/static/openapi/conversations.json"
target.write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(f"Generated {len(paths)} Java conversation paths")
