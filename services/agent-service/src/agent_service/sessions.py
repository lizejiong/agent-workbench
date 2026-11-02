"""Python 自有会话归属与运行租约，不访问 Java 知识库表。"""

import json
import time

import aiosqlite

from agent_service.contracts import AgentError


class Sessions:
    def __init__(self, connection: aiosqlite.Connection):
        self.db = connection

    async def setup(self):
        await self.db.execute("PRAGMA journal_mode=WAL")
        await self.db.execute("PRAGMA busy_timeout=3000")
        await self.db.execute(
            """CREATE TABLE IF NOT EXISTS agent_session (
            id TEXT PRIMARY KEY, tenant_id TEXT NOT NULL, user_id TEXT NOT NULL,
            scope_json TEXT NOT NULL, lease_token TEXT,
            lease_until REAL NOT NULL DEFAULT 0
            )"""
        )
        await self.db.commit()

    async def scope(self, session_id: str) -> dict:
        async with self.db.execute(
            "SELECT scope_json FROM agent_session WHERE id=?", (session_id,)
        ) as cursor:
            row = await cursor.fetchone()
        if row is None:
            raise AgentError(404, "SESSION_NOT_FOUND", "会话不存在或不可访问")
        return json.loads(row[0])

    async def create(self, grant: dict):
        scope = {"kbIds": grant["kbIds"], "documentIds": grant.get("documentIds")}
        await self.db.execute(
            "INSERT INTO agent_session(id,tenant_id,user_id,scope_json) "
            "VALUES(?,?,?,?)",
            (
                grant["agentSessionId"],
                grant["tenantId"],
                grant["userId"],
                json.dumps(scope, separators=(",", ":")),
            ),
        )
        await self.db.commit()

    async def acquire(self, grant: dict, run_id: str, seconds: float):
        async with self.db.execute(
            "SELECT tenant_id,user_id FROM agent_session WHERE id=?",
            (grant["agentSessionId"],),
        ) as cursor:
            owner = await cursor.fetchone()
        if owner != (grant["tenantId"], grant["userId"]):
            raise AgentError(404, "SESSION_NOT_FOUND", "会话不存在或不可访问")
        now = time.time()
        cursor = await self.db.execute(
            """UPDATE agent_session SET lease_token=?,lease_until=?
            WHERE id=? AND tenant_id=? AND user_id=? AND lease_until<=?""",
            (
                run_id,
                now + seconds + 10,
                grant["agentSessionId"],
                grant["tenantId"],
                grant["userId"],
                now,
            ),
        )
        await self.db.commit()
        if cursor.rowcount != 1:
            raise AgentError(409, "SESSION_BUSY", "该会话已有运行中的回合")

    async def release(self, session_id: str, run_id: str):
        await self.db.execute(
            """UPDATE agent_session SET lease_token=NULL,lease_until=0
            WHERE id=? AND lease_token=?""",
            (session_id, run_id),
        )
        await self.db.commit()
