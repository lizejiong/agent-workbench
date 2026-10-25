package com.lzj.rag.backend.api;

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

@Configuration(proxyBeanMethods = false)
public class RequestTracingConfiguration {
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    @Bean
    RequestIdFilter requestIdFilter() {
        return new RequestIdFilter() {
            @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                                       FilterChain chain) throws ServletException, IOException {
                String incoming = request.getHeader(HEADER_NAME);
                String id = incoming != null && SAFE_ID.matcher(incoming).matches()
                        ? incoming : UUID.randomUUID().toString();
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
