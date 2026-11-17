"""SSE v3 的稳定 data-tool 投影。与 Web Zod 契约逐字段校验。"""

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field

from agent_service.tools.contracts import ToolStatus


class ToolEvent(BaseModel):
    model_config = ConfigDict(extra="forbid")
    part: Literal["data-tool"] = "data-tool"
    eventType: Literal["start", "success", "failure", "cancel"]
    callId: str = Field(min_length=1, max_length=128)
    toolKey: str = Field(min_length=1, max_length=200)
    displayName: str = Field(min_length=1, max_length=120)
    batchId: str
    order: int = Field(ge=0, le=31)
    status: Literal["pending", "running"] | ToolStatus
    startedAt: str | None = None
    durationMs: int | None = Field(default=None, ge=0, le=120000)
    summary: str = Field(default="", max_length=1000)
    block: dict
