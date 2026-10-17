"""Small shared contracts for FastAPI services; Redis remains optional."""

from .context import get_request_id
from .errors import ClientError, RemoteError, ServiceError
from .result import Result
from .web import install_web

__all__ = [
    "ClientError",
    "RemoteError",
    "Result",
    "ServiceError",
    "get_request_id",
    "install_web",
]
