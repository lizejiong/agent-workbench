package com.lzj.rag.framework.starter.log.aspect;

import com.alibaba.fastjson2.JSON;
import com.lzj.rag.framework.starter.log.annotation.ILog;
import com.lzj.rag.framework.starter.log.config.LogProperties;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 统一记录带有 {@link ILog} 注解的方法调用。
 */
@Aspect
public class ILogAspect {

    static final String DISABLED_CONTENT = "<disabled>";

    private static final Logger LOGGER = LoggerFactory.getLogger(ILogAspect.class);

    private final LogProperties properties;

    public ILogAspect(LogProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
    }

    /**
     * 记录调用结果或异常，同时保持业务方法原有的返回值和异常语义。
     */
    @Around("@annotation(iLog)")
    public Object logMethodInvocation(ProceedingJoinPoint joinPoint, ILog iLog) throws Throwable {
        String method = methodName(joinPoint);
        String operation = iLog.value().isBlank() ? method : iLog.value();
        Object[] invocationArgs = joinPoint.getArgs();
        String args = LOGGER.isInfoEnabled() ? content(invocationArgs, iLog.recordArgs()) : null;
        long startNanos = System.nanoTime();

        try {
            Object result = joinPoint.proceed();
            long elapsedMillis = elapsedMillis(startNanos);
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info(
                        "Method call succeeded | operation={} | method={} | args={} | result={} | elapsedMs={}",
                        operation,
                        method,
                        args,
                        content(result, iLog.recordResult()),
                        elapsedMillis
                );
            }
            return result;
        } catch (Throwable throwable) {
            long elapsedMillis = elapsedMillis(startNanos);
            if (LOGGER.isErrorEnabled()) {
                String failureArgs = args == null ? content(invocationArgs, iLog.recordArgs()) : args;
                LOGGER.error(
                        "Method call failed | operation={} | method={} | args={} | elapsedMs={} | exception={}",
                        operation,
                        method,
                        failureArgs,
                        elapsedMillis,
                        throwable.toString(),
                        throwable
                );
            }
            throw throwable;
        }
    }

    private String methodName(ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        return signature.getDeclaringType().getSimpleName() + "#" + signature.getName();
    }

    private long elapsedMillis(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    private String content(Object value, boolean enabled) {
        if (!enabled) {
            return DISABLED_CONTENT;
        }
        return truncate(serialize(value));
    }

    private String serialize(Object value) {
        try {
            return JSON.toJSONString(value);
        } catch (RuntimeException serializationException) {
            try {
                return String.valueOf(value);
            } catch (RuntimeException toStringException) {
                return "<unprintable>";
            }
        }
    }

    private String truncate(String value) {
        int maxLength = properties.getMaxContentLength();
        if (value.length() <= maxLength) {
            return value;
        }
        String suffix = "...";
        if (maxLength <= suffix.length()) {
            return value.substring(0, maxLength);
        }
        return value.substring(0, maxLength - suffix.length()) + suffix;
    }
}
