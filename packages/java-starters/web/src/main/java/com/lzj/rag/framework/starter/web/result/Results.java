package com.lzj.rag.framework.starter.web.result;

import com.lzj.rag.framework.convention.errorcode.ErrorCode;
import com.lzj.rag.framework.convention.result.Result;
import com.lzj.rag.framework.starter.web.context.RequestIdContext;

import java.util.UUID;

/**
 * {@link Result} 的快捷构造器，并统一补充请求 ID。
 */
public final class Results {

    private Results() {
    }

    public static <T> Result<T> success() {
        return success(null, null);
    }

    public static <T> Result<T> success(T data) {
        return success(data, null);
    }

    public static <T> Result<T> success(T data, String requestId) {
        return Result.success(data).setRequestId(resolveRequestId(requestId));
    }

    public static <T> Result<T> failure(ErrorCode errorCode) {
        return failure(errorCode, null);
    }

    public static <T> Result<T> failure(ErrorCode errorCode, String requestId) {
        return Result.<T>failure(errorCode).setRequestId(resolveRequestId(requestId));
    }

    public static <T> Result<T> failure(String code, String message) {
        return failure(code, message, null);
    }

    public static <T> Result<T> failure(String code, String message, String requestId) {
        return Result.<T>failure(code, message).setRequestId(resolveRequestId(requestId));
    }

    private static String resolveRequestId(String requestId) {
        if (requestId != null && !requestId.isBlank()) {
            return requestId;
        }
        String currentRequestId = RequestIdContext.get();
        if (currentRequestId != null && !currentRequestId.isBlank()) {
            return currentRequestId;
        }
        return UUID.randomUUID().toString().replace("-", "");
    }
}
