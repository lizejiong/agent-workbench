"""公开请求只表达问题与期望范围；身份和实际授权由 Java 确认。"""

from typing import Annotated, Any, Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, StrictStr, field_validator
from rag_common.errors import AppError


class AgentError(AppError):
    def __init__(self, status: int, code: str, message: str):
        super().__init__(message, code=code)
        self.status_code = status


class ChatRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    question: Annotated[StrictStr, Field(min_length=1, max_length=4000)]
    sessionId: UUID | None = None
    conversationId: UUID | None = None
    requestId: UUID | None = None
    messageId: UUID | None = None
    kbIds: list[UUID] | None = Field(default=None, max_length=64)
    documentIds: list[UUID] | None = Field(default=None, min_length=1, max_length=500)

    @field_validator(
        "sessionId", "conversationId", "requestId", "messageId", mode="before"
    )
    @classmethod
    def session_uuid(cls, value):
        return UUID(value) if isinstance(value, str) else value

    @field_validator("kbIds", "documentIds", mode="before")
    @classmethod
    def scope_uuids(cls, value):
        if isinstance(value, list):
            return [UUID(item) if isinstance(item, str) else item for item in value]
        return value

    @field_validator("question")
    @classmethod
    def clean_question(cls, value):
        if not value.strip() or "\0" in value:
            raise ValueError("问题不能为空或包含 NUL")
        return value.strip()


KnowledgeQuery = Annotated[StrictStr, Field(min_length=1, max_length=4000)]


class Citation(BaseModel):
    model_config = ConfigDict(extra="forbid")
    evidenceId: str
    kbId: UUID
    documentId: UUID
    chunkId: UUID
    generation: int = Field(ge=1)
    documentName: str
    sourceMetadata: dict[str, Any] | None
    snapshotHash: str
    fileUrl: str
    truncated: bool
    toolTruncated: bool


class RetrievalSummary(BaseModel):
    model_config = ConfigDict(extra="forbid")
    status: Literal["READY", "NO_EVIDENCE", "NEEDS_CLARIFICATION", "UNAVAILABLE"]
    requestId: UUID
    traceId: str
    degraded: bool | None = None
    strategy: str | None = None
    toolTruncated: bool | None = None
    errorCode: str | None = None
    retryable: bool | None = None


class AgentReply(BaseModel):
    model_config = ConfigDict(extra="forbid")
    sessionId: UUID
    conversationId: UUID
    runId: UUID
    traceId: str
    status: Literal["RUNNING", "COMPLETED", "CANCELLED", "FAILED", "TIMED_OUT"]
    userMessageId: UUID
    assistantMessageId: UUID
    replayed: bool = False
    persisted: bool = True
    blocks: list[dict[str, Any]] = Field(default_factory=list, max_length=128)
    eventSeq: int = 0
    sourcesRestricted: bool = False
    answer: str
    citations: list[Citation]
    retrievals: list[RetrievalSummary]
    model: str
    promptVersion: str
    promptHash: str
