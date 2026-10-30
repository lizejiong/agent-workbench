package com.lzj.rag.ai.infra.provider.exception;

import com.lzj.rag.framework.convention.errorcode.ErrorCode;
import com.lzj.rag.framework.convention.exception.ServiceException;
import com.lzj.rag.framework.starter.web.exception.HttpStatusAware;

/** 模型调用异常，继承公共异常契约，并保留 HTTP 状态、重试和熔断分类。 */
public class ModelInvocationException extends ServiceException implements HttpStatusAware {

    /** 对外使用的 HTTP 状态码。 */
    private final int status;

    /** 是否允许在剩余预算内换候选重试。 */
    private final boolean retryable;

    /** 本次失败是否计入模型熔断失败次数。 */
    private final boolean counted;

    /**
     * 创建模型调用异常，同时写入公共错误码契约和本次错误的控制标记。
     *
     * @param status 对外使用的 HTTP 状态码。
     * @param category 稳定的模型错误分类，同时作为公共错误码。
     * @param message 需要保留给调用方或启动诊断的错误说明。
     * @param retryable 是否允许在剩余预算内换候选重试。
     * @param counted 本次失败是否计入模型熔断失败次数。
     */
    public ModelInvocationException(
            int status, String category, String message, boolean retryable, boolean counted) {
        super(message, new InvocationErrorCode(category, message));
        this.status = status;
        this.retryable = retryable;
        this.counted = counted;
    }

    /**
     * 返回对外使用的 HTTP 状态。
     *
     * @return 错误对应的 HTTP 状态码。
     */
    public int status() {
        return status;
    }

    /**
     * 通过公共接口提供异常的 HTTP 状态映射。
     *
     * @return 公共异常处理器应使用的 HTTP 状态码。
     */
    @Override
    public int httpStatus() {
        return status;
    }

    /**
     * 返回供接口、日志和流事件使用的稳定错误分类。
     *
     * @return 继承自公共异常体系的错误码。
     */
    public String category() {
        return getErrorCode();
    }

    /**
     * 判断当前错误是否允许在预算内尝试下一个候选。
     *
     * @return 允许降级时为 true。
     */
    public boolean retryable() {
        return retryable;
    }

    /**
     * 判断当前错误是否计入模型熔断失败次数。
     *
     * @return 需要增加熔断失败计数时为 true。
     */
    public boolean counted() {
        return counted;
    }

    /**
     * 将模型错误分类适配为公共错误码接口。
     *
     * @param code 公共错误契约中的错误码。
     * @param message 需要保留给调用方或启动诊断的错误说明。
     */
    private record InvocationErrorCode(String code, String message) implements ErrorCode {}

    /**
     * 创建不可重试、且不计入熔断的输入错误。
     *
     * @param message 需要保留给调用方或启动诊断的错误说明。
     * @return HTTP 400 的输入异常。
     */
    public static ModelInvocationException input(String message) {
        return new ModelInvocationException(400, "INVALID_INPUT", message, false, false);
    }

    /**
     * 创建模型或路由暂不可用的错误。
     *
     * @param message 需要保留给调用方或启动诊断的错误说明。
     * @return HTTP 503 的可用性异常。
     */
    public static ModelInvocationException unavailable(String message) {
        return new ModelInvocationException(503, "UNAVAILABLE", message, true, false);
    }

    /**
     * 创建供应商响应结构或内容不合法的错误。
     *
     * @return HTTP 502 的可重试异常，并计入熔断。
     */
    public static ModelInvocationException invalid() {
        return new ModelInvocationException(
                502, "INVALID_RESPONSE", "Invalid upstream response", true, true);
    }

    /**
     * 创建调用超时错误。
     *
     * @return HTTP 504 的可重试异常，并计入熔断。
     */
    public static ModelInvocationException timeout() {
        return new ModelInvocationException(504, "TIMEOUT", "AI request timed out", true, true);
    }

    /**
     * 将技术异常归一化为模型错误分类；取消信号必须继续传播，不能当作失败重试。
     *
     * @param error 当前响应式阶段收到的失败原因，按上下文归类并决定是否允许降级。
     * @return 原模型异常，或按超时、协议、网络、内部错误归类的新异常。
     */
    public static ModelInvocationException classify(Throwable error) {
        if (error instanceof java.util.concurrent.CancellationException
                || reactor.core.Exceptions.isCancel(error))
            throw reactor.core.Exceptions.propagate(error);
        // e：已经归类的模型异常，直接保留其状态与重试信息。
        if (error instanceof ModelInvocationException e) return e;
        if (error instanceof java.util.concurrent.TimeoutException) return timeout();
        if (error instanceof org.springframework.core.io.buffer.DataBufferLimitException
                || error instanceof org.springframework.core.codec.DecodingException)
            return invalid();
        if (error
                        instanceof
                        org.springframework.web.reactive.function.client.WebClientRequestException
                || error instanceof java.io.IOException)
            return new ModelInvocationException(
                    502, "NETWORK", "Upstream connection failed", true, true);
        return new ModelInvocationException(
                500, "INTERNAL", "Internal AI service error", false, false);
    }
}
