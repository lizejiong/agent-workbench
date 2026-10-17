package com.lzj.rag.framework.convention.exception;

import com.lzj.rag.framework.convention.errorcode.BaseErrorCode;
import com.lzj.rag.framework.convention.errorcode.ErrorCode;

/**
 * Exception caused by a failure inside the current service.
 */
public class ServiceException extends AbstractException {

    public ServiceException(String message) {
        this(message, null, BaseErrorCode.SERVICE_ERROR);
    }

    public ServiceException(ErrorCode errorCode) {
        this(null, null, errorCode);
    }

    public ServiceException(String message, ErrorCode errorCode) {
        this(message, null, errorCode);
    }

    public ServiceException(String message, Throwable cause, ErrorCode errorCode) {
        super(message, cause, errorCode);
    }
}
