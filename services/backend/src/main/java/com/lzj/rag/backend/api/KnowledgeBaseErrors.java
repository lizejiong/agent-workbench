package com.lzj.rag.backend.api;

import com.lzj.rag.backend.knowledgebase.KnowledgeBaseException;
import com.lzj.rag.framework.convention.result.Result;
import com.lzj.rag.framework.starter.web.result.Results;
import com.lzj.rag.framework.starter.web.context.RequestIdContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.*;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Preserve the shared JSON envelope while returning meaningful HTTP status codes in this service. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class KnowledgeBaseErrors extends ResponseEntityExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(KnowledgeBaseErrors.class);

    @ExceptionHandler(KnowledgeBaseException.class)
    ResponseEntity<Result<Void>> business(KnowledgeBaseException error) {
        return error(error.status(), error.code(), error.getMessage());
    }

    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<Result<Void>> duplicate(DuplicateKeyException error) {
        return error(409, "KB_NAME_EXISTS", "A knowledge base with this name already exists");
    }

    @ExceptionHandler({DataAccessException.class, CannotCreateTransactionException.class, TransactionTimedOutException.class})
    ResponseEntity<Result<Void>> unavailable(Exception error) {
        LOG.error("Knowledge base persistence failure requestId={} type={}", RequestIdContext.get(), error.getClass().getSimpleName());
        return error(503, "KB_DATABASE_UNAVAILABLE", "Database operation unavailable; retry later");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Result<Void>> unexpected(Exception error) {
        LOG.error("Knowledge base request failed requestId={} type={}", RequestIdContext.get(), error.getClass().getSimpleName());
        return error(500, "KB_INTERNAL", "Internal knowledge base service error");
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception error, Object body, HttpHeaders headers,
                                                              HttpStatusCode status, WebRequest request) {
        String code = status.value() == 400 ? "KB_INVALID_INPUT" : "KB_HTTP_" + status.value();
        String message = status.value() == 400 ? "Invalid request parameters or body" : "HTTP request rejected";
        if (status.value() == 413) { code = "KB_UPLOAD_TOO_LARGE"; message = "File or multipart request exceeds configured upload limit"; }
        return new ResponseEntity<>(Results.failure(code, message), headers, status);
    }

    private ResponseEntity<Result<Void>> error(int status, String code, String message) {
        return ResponseEntity.status(status).body(Results.failure(code, message));
    }
}
