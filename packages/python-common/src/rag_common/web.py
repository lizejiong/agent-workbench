"""Opt-in FastAPI handlers and streaming-safe request context middleware."""

import logging
import re
from time import perf_counter
from uuid import uuid4

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from starlette.datastructures import Headers, MutableHeaders
from starlette.exceptions import HTTPException
from starlette.responses import JSONResponse
from starlette.types import ASGIApp, Message, Receive, Scope, Send

from .context import request_id_context
from .errors import AppError, ClientError, ServiceError
from .result import Result

logger = logging.getLogger(__name__)
_VALID_REQUEST_ID = re.compile(r"[A-Za-z0-9._:-]{1,128}", re.ASCII)


class RequestContextMiddleware:
    def __init__(self, app: ASGIApp) -> None:
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        incoming = Headers(scope=scope).get("x-request-id", "")
        request_id = incoming if _VALID_REQUEST_ID.fullmatch(incoming) else uuid4().hex
        scope.setdefault("state", {})["request_id"] = request_id
        status = 500
        started_at = perf_counter()

        async def send_with_request_id(message: Message) -> None:
            nonlocal status
            if message["type"] == "http.response.start":
                status = message["status"]
                MutableHeaders(scope=message)["X-Request-Id"] = request_id
            await send(message)

        with request_id_context(request_id):
            try:
                await self.app(scope, receive, send_with_request_id)
            finally:
                logger.info(
                    "request method=%s path=%s status=%d "
                    "duration_ms=%.3f request_id=%s",
                    scope["method"],
                    scope["path"],
                    status,
                    (perf_counter() - started_at) * 1000,
                    request_id,
                )


def _error_response(
    request: Request,
    *,
    status: int,
    code: str,
    message: str,
    headers: dict[str, str] | None = None,
) -> JSONResponse:
    # ServerErrorMiddleware invokes the generic handler after our context has reset.
    result = Result.failure(
        code, message, request_id=getattr(request.state, "request_id", None)
    )
    response = JSONResponse(
        result.model_dump(mode="json"), status_code=status, headers=headers
    )
    response.headers["X-Request-Id"] = result.request_id
    return response


async def _app_error(request: Request, exc: AppError) -> JSONResponse:
    return _error_response(
        request, status=exc.status_code, code=exc.code, message=exc.message
    )


async def _validation_error(
    request: Request, exc: RequestValidationError
) -> JSONResponse:
    return _error_response(
        request, status=422, code=ClientError.code, message="请求参数校验失败"
    )


async def _http_error(request: Request, exc: HTTPException) -> JSONResponse:
    category = ServiceError if exc.status_code >= 500 else ClientError
    return _error_response(
        request,
        status=exc.status_code,
        code=category.code,
        message=str(exc.detail),
        headers=exc.headers,
    )


async def _unexpected_error(request: Request, exc: Exception) -> JSONResponse:
    logger.error(
        "Unhandled request exception request_id=%s",
        getattr(request.state, "request_id", None),
        exc_info=(type(exc), exc, exc.__traceback__),
    )
    return _error_response(
        request, status=500, code=ServiceError.code, message=ServiceError.message
    )


def install_web(app: FastAPI) -> None:
    """Install once, before the application's middleware stack starts."""
    if getattr(app.state, "_rag_common_web_installed", False):
        raise RuntimeError("rag_common web support is already installed")
    if app.middleware_stack is not None:
        raise RuntimeError("install_web must be called before application startup")
    app.add_exception_handler(AppError, _app_error)
    app.add_exception_handler(RequestValidationError, _validation_error)
    app.add_exception_handler(HTTPException, _http_error)
    app.add_exception_handler(Exception, _unexpected_error)
    app.add_middleware(RequestContextMiddleware)
    app.state._rag_common_web_installed = True
