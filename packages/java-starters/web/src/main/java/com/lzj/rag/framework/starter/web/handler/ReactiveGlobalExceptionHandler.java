package com.lzj.rag.framework.starter.web.handler;

import com.lzj.rag.framework.convention.exception.AbstractException;
import com.lzj.rag.framework.convention.exception.ClientException;
import com.lzj.rag.framework.convention.exception.RemoteException;
import com.lzj.rag.framework.convention.result.Result;
import com.lzj.rag.framework.starter.web.config.ReactiveWebProperties;
import com.lzj.rag.framework.starter.web.context.ReactiveRequestIdContext;
import com.lzj.rag.framework.starter.web.exception.HttpStatusAware;
import com.lzj.rag.framework.starter.web.result.Results;
import org.springframework.core.codec.DecodingException;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;

/** Shared reactive counterpart to the MVC exception handler. */
@RestControllerAdvice
public class ReactiveGlobalExceptionHandler {
    private final ReactiveWebProperties properties;

    public ReactiveGlobalExceptionHandler(ReactiveWebProperties properties) { this.properties = properties; }

    @ExceptionHandler(AbstractException.class)
    public ResponseEntity<Result<Void>> business(AbstractException error, ServerWebExchange exchange) {
        int status = error instanceof HttpStatusAware explicit ? explicit.httpStatus()
                : error instanceof ClientException ? 400 : error instanceof RemoteException ? 502 : 500;
        return response(status, error.getErrorCode(), error.getErrorMessage(), exchange);
    }

    @ExceptionHandler({ServerWebInputException.class, DecodingException.class, DataBufferLimitException.class})
    public ResponseEntity<Result<Void>> input(Exception error, ServerWebExchange exchange) {
        return response(400, properties.getInputCode(), properties.getInputMessage(), exchange);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> unexpected(Exception error, ServerWebExchange exchange) {
        return response(500, properties.getInternalCode(), properties.getInternalMessage(), exchange);
    }

    private ResponseEntity<Result<Void>> response(int status, String code, String message, ServerWebExchange exchange) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .body(Results.failure(code, message, ReactiveRequestIdContext.get(exchange)));
    }
}
