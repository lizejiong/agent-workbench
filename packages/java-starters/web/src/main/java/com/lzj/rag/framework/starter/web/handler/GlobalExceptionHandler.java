package com.lzj.rag.framework.starter.web.handler;

import com.lzj.rag.framework.convention.errorcode.BaseErrorCode;
import com.lzj.rag.framework.convention.exception.AbstractException;
import com.lzj.rag.framework.convention.result.Result;
import com.lzj.rag.framework.starter.web.result.Results;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 将参数异常、项目自定义异常和未知异常转换为统一 {@link Result} 响应。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler({
            BindException.class,
            ServletRequestBindingException.class,
            HttpMessageNotReadableException.class,
            TypeMismatchException.class
    })
    public Result<Void> handleParameterException(Exception exception) {
        String message = BaseErrorCode.CLIENT_ERROR.message();
        if (exception instanceof BindException bindException) {
            message = bindException.getBindingResult().getFieldErrors().stream()
                    .findFirst()
                    .map(error -> error.getField() + ": " + error.getDefaultMessage())
                    .orElse(message);
        }
        return Results.failure(BaseErrorCode.CLIENT_ERROR.code(), message);
    }

    @ExceptionHandler(AbstractException.class)
    public Result<Void> handleAbstractException(AbstractException exception) {
        return Results.failure(exception.getErrorCode(), exception.getErrorMessage());
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception exception) {
        LOGGER.error("系统未知异常", exception);
        return Results.failure(BaseErrorCode.SERVICE_ERROR);
    }
}
