package com.lzj.rag.framework.convention.result;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.lzj.rag.framework.convention.errorcode.ErrorCode;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;

/**
 * Common response envelope used by rag service APIs.
 */
@Data
@Accessors(chain = true)
@NoArgsConstructor
@AllArgsConstructor
public class Result<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public static final String SUCCESS_CODE = "0";
    public static final String SUCCESS_MESSAGE = "success";

    private String code;
    private String message;
    private T data;
    private String requestId;

    public static <T> Result<T> success() {
        return success(null);
    }

    public static <T> Result<T> success(T data) {
        return new Result<T>()
                .setCode(SUCCESS_CODE)
                .setMessage(SUCCESS_MESSAGE)
                .setData(data);
    }

    public static <T> Result<T> failure(ErrorCode errorCode) {
        Objects.requireNonNull(errorCode, "errorCode must not be null");
        return failure(errorCode.code(), errorCode.message());
    }

    public static <T> Result<T> failure(String code, String message) {
        return new Result<T>()
                .setCode(Objects.requireNonNull(code, "code must not be null"))
                .setMessage(Objects.requireNonNull(message, "message must not be null"));
    }

    @JsonIgnore
    public boolean isSuccess() {
        return SUCCESS_CODE.equals(code);
    }
}
