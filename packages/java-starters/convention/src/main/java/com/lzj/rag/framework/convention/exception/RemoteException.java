package com.lzj.rag.framework.convention.exception;

import com.lzj.rag.framework.convention.errorcode.BaseErrorCode;
import com.lzj.rag.framework.convention.errorcode.ErrorCode;

/**
 * Exception caused by a remote service or third-party dependency.
 */
public class RemoteException extends AbstractException {

    public RemoteException(String message) {
        this(message, null, BaseErrorCode.REMOTE_ERROR);
    }

    public RemoteException(ErrorCode errorCode) {
        this(null, null, errorCode);
    }

    public RemoteException(String message, ErrorCode errorCode) {
        this(message, null, errorCode);
    }

    public RemoteException(String message, Throwable cause, ErrorCode errorCode) {
        super(message, cause, errorCode);
    }
}
