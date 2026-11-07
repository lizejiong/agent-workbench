"""Java 会话接口客户端；业务消息和执行租约不在 Python 另建账本。"""

import time
import uuid
from datetime import UTC, datetime, timedelta

import httpx

from agent_service.contracts import AgentError


class ConversationClient:
    def __init__(self, gateway):
        self.gateway = gateway
        self.client = gateway.client

    async def _read(self, method, path, *, identity=None, auth=None, body=None):
        headers = None
        if identity:
            # 结算使用独立的短 HTTP 预算；不会延长 Java 保存的执行 deadline。
            headers = self.gateway._headers(identity, str(uuid.uuid4()), 3)
        try:
            response = await self.client.request(
                method,
                path,
                headers=headers,
                auth=auth,
                json=body,
                timeout=httpx.Timeout(3, connect=1, pool=1),
            )
        except httpx.HTTPError as exc:
            raise AgentError(503, "HISTORY_UNAVAILABLE", "会话服务暂不可用") from exc
        if not response.is_success:
            code = "HISTORY_UNAVAILABLE"
            try:
                candidate = response.json().get("code")
                if candidate in {
                    "SESSION_BUSY",
                    "AGENT_REQUEST_CONFLICT",
                    "AGENT_MESSAGE_CONFLICT",
                    "AGENT_CHECKPOINT_CONFLICT",
                    "CONVERSATION_NOT_FOUND",
                    "CONVERSATION_SCOPE_CHANGED",
                    "EVIDENCE_CHANGED",
                    "CITATION_INVALID",
                    "CONTEXT_BUDGET",
                    "AGENT_SUMMARY_CONFLICT",
                    "AGENT_SUMMARY_SOURCES",
                    "AGENT_SUMMARY_DEPENDENCIES",
                    "AGENT_RUN_ENDED",
                }:
                    code = candidate
            except (ValueError, AttributeError):
                pass
            status = response.status_code
            raise AgentError(
                status if status in {400, 401, 403, 404, 409, 413} else 503,
                code,
                "会话记录不可用或执行状态已变化",
            )
        try:
            result = response.json()
            if result["code"] != "0":
                raise ValueError
            return result["data"]
        except (ValueError, KeyError, TypeError) as exc:
            raise AgentError(502, "INVALID_HISTORY", "会话响应无效") from exc

    async def create(self, request, auth):
        return await self._read(
            "POST",
            "/api/conversations",
            auth=auth,
            body={
                "requestId": str(request.requestId or uuid.uuid4()),
                "kbIds": None
                if request.kbIds is None
                else list(map(str, request.kbIds)),
                "documentIds": None
                if request.documentIds is None
                else list(map(str, request.documentIds)),
            },
        )

    def path(self, identity):
        return f"/internal/agent/conversations/{identity.agent_session_id}"

    async def begin(self, identity, request):
        seconds = max(0.001, identity.deadline - time.monotonic())
        return await self._read(
            "POST",
            self.path(identity) + "/runs",
            identity=identity,
            body={
                "requestId": str(request.requestId or uuid.uuid4()),
                "messageId": str(request.messageId or uuid.uuid4()),
                "question": request.question,
                "deadline": (
                    datetime.now(UTC) + timedelta(seconds=seconds)
                ).isoformat(),
            },
        )

    async def history(self, identity):
        return await self._read(
            "GET", self.path(identity) + "/history", identity=identity
        )

    async def history_turns(self, identity, run_id, limit):
        turns, before, upper = [], None, None
        while len(turns) < limit:
            size = min(4, limit - len(turns))
            path = (
                self.path(identity) + f"/history?view=turns&runId={run_id}&size={size}"
            )
            if before is not None:
                path += f"&beforeSequence={before}"
            page = await self._read("GET", path, identity=identity)
            try:
                boundary = page["upperSequence"]
                cursor = page["nextBeforeSequence"]
                rows = page["turns"]
                if (
                    not isinstance(boundary, int)
                    or boundary < 1
                    or upper is not None
                    and upper != boundary
                    or not isinstance(rows, list)
                    or len(rows) > size
                    or not isinstance(cursor, int)
                    or cursor < 0
                    or before is not None
                    and rows
                    and cursor >= before
                    or rows
                    and cursor >= boundary
                    or not rows
                    and page["hasMore"]
                ):
                    raise ValueError
                upper = boundary
                turns = rows + turns
                if not page["hasMore"] or not rows:
                    return turns
                before = cursor
            except (KeyError, TypeError, ValueError) as exc:
                raise AgentError(502, "INVALID_HISTORY", "历史分页响应无效") from exc
        return turns

    async def state(self, identity, run_id):
        return await self._read(
            "GET", self.path(identity) + f"/runs/{run_id}", identity=identity
        )

    async def summary(self, identity, run_id, retained, policy):
        return await self._read(
            "GET",
            self.path(identity)
            + f"/context-summary?runId={run_id}&retainedTurns={retained}"
            + f"&policyVersion={policy}",
            identity=identity,
        )

    async def save_summary(self, identity, body):
        return await self._read(
            "PUT",
            self.path(identity) + "/context-summary",
            identity=identity,
            body=body,
        )

    async def use_summary(self, identity, body):
        return await self._read(
            "POST",
            self.path(identity) + "/context-summary/use",
            identity=identity,
            body=body,
        )

    async def save(self, identity, run_id, snapshot):
        return await self._read(
            "PATCH",
            self.path(identity) + f"/runs/{run_id}",
            identity=identity,
            body=snapshot,
        )
