package com.lzj.rag.backend.security.config;

import com.lzj.rag.framework.starter.web.context.RequestIdContext;
import com.lzj.rag.framework.starter.web.filter.RequestIdFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/** 为业务请求补齐跟踪标识，并在请求完成后清理线程上下文。 */
@Configuration(proxyBeanMethods = false)
public class RequestTracingConfiguration {

    /** 用于校验公开标识合法性的正则表达式。 */
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    /**
     * 注册请求跟踪过滤器，设置响应头及日志上下文。
     *
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @Bean
    RequestIdFilter requestIdFilter() {
        return new RequestIdFilter() {

            /**
             * 校验或生成请求标识，执行过滤器链后清理 MDC。
             *
             * @param request 当前 Servlet 请求。
             * @param response 需要写入状态及公共响应的 Servlet 响应。
             * @param chain 继续执行当前请求的 Servlet 过滤器链。
             */
            @Override
            protected void doFilterInternal(
                    HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                // incoming：调用方传入的原始请求标识。
                String incoming = request.getHeader(HEADER_NAME);
                // id：经格式校验或服务端生成的当前请求跟踪标识。
                String id =
                        incoming != null && SAFE_ID.matcher(incoming).matches()
                                ? incoming
                                : UUID.randomUUID().toString();
                RequestIdContext.set(id);
                response.setHeader(HEADER_NAME, id);
                try {
                    chain.doFilter(request, response);
                } finally {
                    RequestIdContext.clear();
                }
            }
        };
    }
}
