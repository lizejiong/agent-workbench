package com.lzj.rag.framework.starter.web.filter;

import com.lzj.rag.framework.starter.web.context.ReactiveRequestIdContext;
import org.springframework.core.Ordered;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import java.util.UUID;

/** Validates or generates a request ID without using thread-local state. */
public class ReactiveRequestIdFilter implements WebFilter, Ordered {
    @Override
    public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String incoming = exchange.getRequest().getHeaders().getFirst(ReactiveRequestIdContext.HEADER_NAME);
        String requestId = incoming != null && incoming.matches("[A-Za-z0-9._:-]{1,128}")
                ? incoming : UUID.randomUUID().toString().replace("-", "");
        exchange.getAttributes().put(ReactiveRequestIdContext.KEY, requestId);
        exchange.getResponse().getHeaders().set(ReactiveRequestIdContext.HEADER_NAME, requestId);
        return chain.filter(exchange)
                .contextWrite(context -> context.put(ReactiveRequestIdContext.KEY, requestId));
    }
}
