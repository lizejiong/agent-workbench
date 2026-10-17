package com.lzj.rag.framework.convention.exception;

import com.lzj.rag.framework.convention.errorcode.BaseErrorCode;
import com.lzj.rag.framework.convention.errorcode.ErrorCode;

/**
 * Exception caused by invalid client input or state.
 */
public class ClientException extends AbstractException {

    public ClientException(String message) {
        this(message, null, BaseErrorCode.CLIENT_ERROR);
    }

    public ClientException(ErrorCode errorCode) {
        this(null, null, errorCode);
    }

    public ClientException(String message, ErrorCode errorCode) {
        this(message, null, errorCode);
    }

    public ClientException(String message, Throwable cause, ErrorCode errorCode) {
        super(message, cause, errorCode);
    }
}
