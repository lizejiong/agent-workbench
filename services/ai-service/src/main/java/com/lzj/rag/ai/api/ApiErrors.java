package com.lzj.rag.ai.api;

import com.lzj.rag.ai.service.AiException;
import com.lzj.rag.framework.convention.result.Result;
import org.springframework.core.codec.DecodingException;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.*;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(AiException.class)
    public ResponseEntity<Result<Void>> ai(AiException error, ServerWebExchange exchange) {
        return response(error.status(), error.category(), error.getMessage(), exchange);
    }
    @ExceptionHandler({ServerWebInputException.class, DecodingException.class, DataBufferLimitException.class})
    public ResponseEntity<Result<Void>> input(Exception error, ServerWebExchange exchange) {
        return response(400, "INVALID_INPUT", "Malformed request or body exceeds limit", exchange);
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> unexpected(Exception error, ServerWebExchange exchange) {
        return response(500, "INTERNAL", "Internal AI service error", exchange);
    }
    private ResponseEntity<Result<Void>> response(int status, String code, String message, ServerWebExchange exchange) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .body(Result.<Void>failure(code, message).setRequestId(AiController.id(exchange)));
    }
}
