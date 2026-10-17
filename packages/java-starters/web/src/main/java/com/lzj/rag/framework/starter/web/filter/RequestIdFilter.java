package com.lzj.rag.framework.starter.web.filter;

import com.lzj.rag.framework.starter.web.context.RequestIdContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/** 为同步请求建立关联 ID，并在退出过滤器时清理线程上下文。 */
public class RequestIdFilter extends OncePerRequestFilter implements Ordered {

    public static final String HEADER_NAME = "X-Request-Id";

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String incomingId = request.getHeader(HEADER_NAME);
        String requestId = incomingId == null || incomingId.isBlank()
                ? UUID.randomUUID().toString().replace("-", "") : incomingId;
        RequestIdContext.set(requestId);
        try {
            response.setHeader(HEADER_NAME, requestId);
            filterChain.doFilter(request, response);
        } finally {
            RequestIdContext.clear();
        }
    }
}
