"""Request-local IDs shared by asynchronous and synchronous routes."""

from collections.abc import Iterator
from contextlib import contextmanager
from contextvars import ContextVar

_request_id: ContextVar[str | None] = ContextVar("rag_common_request_id", default=None)


def get_request_id() -> str | None:
    return _request_id.get()


@contextmanager
def request_id_context(request_id: str) -> Iterator[None]:
    """Set an ID for this context and restore the previous value on exit."""
    token = _request_id.set(request_id)
    try:
        yield
    finally:
        _request_id.reset(token)
