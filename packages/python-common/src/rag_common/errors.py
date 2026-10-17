"""Application errors with stable codes, safe messages and HTTP status codes."""


class AppError(Exception):
    code = "B000001"
    message = "系统执行错误"
    status_code = 500

    def __init__(self, message: str | None = None, *, code: str | None = None) -> None:
        self.message = message if message is not None else type(self).message
        self.code = code if code is not None else type(self).code
        super().__init__(self.message)


class ClientError(AppError):
    code = "A000001"
    message = "客户端请求错误"
    status_code = 400


class ServiceError(AppError):
    pass


class RemoteError(AppError):
    code = "C000001"
    message = "远程服务调用错误"
    status_code = 502
