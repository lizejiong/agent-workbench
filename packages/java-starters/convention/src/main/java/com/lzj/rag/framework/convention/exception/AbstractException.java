package com.lzj.rag.framework.convention.exception;

import com.lzj.rag.framework.convention.errorcode.ErrorCode;
import lombok.Getter;

import java.util.Objects;

/**
 * Base type for client, service, and remote invocation exceptions.
 */
@Getter
public abstract class AbstractException extends RuntimeException {

    private final String errorCode;
    private final String errorMessage;

    protected AbstractException(String message, Throwable cause, ErrorCode errorCode) {
        super(resolveMessage(message, errorCode), cause);
        this.errorCode = Objects.requireNonNull(errorCode.code(), "error code must not be null");
        this.errorMessage = getMessage();
    }

    private static String resolveMessage(String message, ErrorCode errorCode) {
        Objects.requireNonNull(errorCode, "errorCode must not be null");
        if (message != null && !message.isBlank()) {
            return message;
        }
        return Objects.requireNonNull(errorCode.message(), "error message must not be null");
    }
}
