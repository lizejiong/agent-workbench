package com.lzj.rag.backend.knowledgebase.exception;

/** 业务异常，携带稳定错误码和公共异常处理器使用的 HTTP 状态。 */
public class KnowledgeBaseException extends RuntimeException {

    /** 本次外部 HTTP 响应或错误映射使用的状态码。 */
    private final int status;

    /** 对外使用的稳定业务错误分类。 */
    private final String code;

    /**
     * 初始化当前组件，保存业务依赖及本次使用的配置。
     *
     * @param status 本次外部 HTTP 响应或错误映射使用的状态码。
     * @param code 对外使用的稳定业务错误分类。
     * @param message 对外展示的业务错误或状态说明。
     */
    public KnowledgeBaseException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    /**
     * 返回当前业务异常的 HTTP 状态码。
     *
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public int status() {
        return status;
    }

    /**
     * 返回当前业务异常的稳定错误码。
     *
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public String code() {
        return code;
    }
}
