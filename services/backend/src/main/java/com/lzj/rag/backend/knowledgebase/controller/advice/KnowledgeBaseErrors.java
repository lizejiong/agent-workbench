package com.lzj.rag.backend.knowledgebase.controller.advice;

import com.lzj.rag.backend.knowledgebase.exception.KnowledgeBaseException;
import com.lzj.rag.framework.convention.result.Result;
import com.lzj.rag.framework.starter.web.context.RequestIdContext;
import com.lzj.rag.framework.starter.web.result.Results;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.*;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** 将业务、数据库及 MVC 异常转换为公共响应契约，隐藏内部错误详情。 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class KnowledgeBaseErrors extends ResponseEntityExceptionHandler {

    /** 当前组件使用的日志记录器。 */
    private static final Logger LOG = LoggerFactory.getLogger(KnowledgeBaseErrors.class);

    /**
     * 按业务异常携带的状态及错误码返回公共错误响应。
     *
     * @param error 已归类的业务异常，保留稳定错误码与 HTTP 状态。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @ExceptionHandler(KnowledgeBaseException.class)
    ResponseEntity<Result<Void>> business(KnowledgeBaseException error) {
        return error(error.status(), error.code(), error.getMessage());
    }

    /**
     * 将唯一性约束冲突转换为可理解的业务冲突响应。
     *
     * @param error 本次处理捕获的异常，用于归类失败原因。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<Result<Void>> duplicate(DuplicateKeyException error) {
        return error(409, "KB_NAME_EXISTS", "A knowledge base with this name already exists");
    }

    /**
     * 将数据库连接或事务资源故障转换为暂不可用响应。
     *
     * @param error 本次处理捕获的异常，用于归类失败原因。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @ExceptionHandler({
        DataAccessException.class,
        CannotCreateTransactionException.class,
        TransactionTimedOutException.class
    })
    ResponseEntity<Result<Void>> unavailable(Exception error) {
        LOG.error(
                "Knowledge base persistence failure requestId={} type={}",
                RequestIdContext.get(),
                error.getClass().getSimpleName());
        return error(503, "KB_DATABASE_UNAVAILABLE", "Database operation unavailable; retry later");
    }

    /**
     * 记录未预期异常并返回不含内部细节的错误响应。
     *
     * @param error 本次处理捕获的异常，用于归类失败原因。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<Result<Void>> unexpected(Exception error) {
        LOG.error(
                "Knowledge base request failed requestId={} type={}",
                RequestIdContext.get(),
                error.getClass().getSimpleName());
        return error(500, "KB_INTERNAL", "Internal knowledge base service error");
    }

    /**
     * 统一转换 MVC 参数、请求格式等框架异常。
     *
     * @param error 本次处理捕获的异常，用于归类失败原因。
     * @param body 需要发送或当前接收的 HTTP 正文。
     * @param headers HTTP 响应头集合。
     * @param status MVC 框架异常对应的 HTTP 状态。
     * @param request MVC 框架传入的当前请求上下文。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception error,
            Object body,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        // code：对外使用的稳定业务错误分类。
        String code = status.value() == 400 ? "KB_INVALID_INPUT" : "KB_HTTP_" + status.value();
        // message：对外展示的业务错误或状态说明。
        String message =
                status.value() == 400
                        ? "Invalid request parameters or body"
                        : "HTTP request rejected";
        if (status.value() == 413) {
            code = "KB_UPLOAD_TOO_LARGE";
            message = "File or multipart request exceeds configured upload limit";
        }
        return new ResponseEntity<>(Results.failure(code, message), headers, status);
    }

    /**
     * 构造附带请求跟踪标识的公共错误响应。
     *
     * @param status 本次外部 HTTP 响应或错误映射使用的状态码。
     * @param code 对外使用的稳定业务错误分类。
     * @param message 对外展示的业务错误或状态说明。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private ResponseEntity<Result<Void>> error(int status, String code, String message) {
        return ResponseEntity.status(status).body(Results.failure(code, message));
    }
}
