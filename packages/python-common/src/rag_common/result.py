"""The four-field JSON response contract shared with Java services."""

from uuid import uuid4

from pydantic import BaseModel, ConfigDict, Field

from .context import get_request_id


def _resolve_request_id(explicit: str | None = None) -> str:
    if explicit is not None and explicit.strip():
        return explicit
    current = get_request_id()
    return current if current is not None and current.strip() else uuid4().hex


class Result[T](BaseModel):
    model_config = ConfigDict(populate_by_name=True, serialize_by_alias=True)

    code: str
    message: str
    data: T | None = None
    request_id: str = Field(default_factory=_resolve_request_id, alias="requestId")

    @classmethod
    def success(
        cls, data: T | None = None, *, request_id: str | None = None
    ) -> "Result[T]":
        return cls(
            code="0",
            message="success",
            data=data,
            request_id=_resolve_request_id(request_id),
        )

    @classmethod
    def failure(
        cls, code: str, message: str, *, request_id: str | None = None
    ) -> "Result[T]":
        return cls(
            code=code, message=message, request_id=_resolve_request_id(request_id)
        )
