package com.lzj.rag.framework.convention.errorcode;

/**
 * Stable error contract shared by common and business-specific error codes.
 */
public interface ErrorCode {

    String code();

    String message();
}
