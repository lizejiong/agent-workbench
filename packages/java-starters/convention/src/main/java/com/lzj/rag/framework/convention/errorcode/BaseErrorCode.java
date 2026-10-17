package com.lzj.rag.framework.convention.errorcode;

/**
 * Top-level error categories based on the Alibaba error source convention.
 */
public enum BaseErrorCode implements ErrorCode {

    CLIENT_ERROR("A000001", "客户端请求错误"),
    SERVICE_ERROR("B000001", "系统执行错误"),
    REMOTE_ERROR("C000001", "远程服务调用错误");

    private final String code;
    private final String message;

    BaseErrorCode(String code, String message) {
        this.code = code;
        this.message = message;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String message() {
        return message;
    }
}
