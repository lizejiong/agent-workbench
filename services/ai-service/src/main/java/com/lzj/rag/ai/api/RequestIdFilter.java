package com.lzj.rag.ai.api;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.*;
import reactor.core.publisher.Mono;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter implements WebFilter {
    public static final String KEY = "ai.requestId";
    @Override public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String incoming = exchange.getRequest().getHeaders().getFirst("X-Request-Id");
        String requestId = incoming != null && incoming.matches("[A-Za-z0-9._:-]{1,128}")
                ? incoming : UUID.randomUUID().toString().replace("-", "");
        exchange.getAttributes().put(KEY, requestId);
        exchange.getResponse().getHeaders().set("X-Request-Id", requestId);
        return chain.filter(exchange).contextWrite(context -> context.put(KEY, requestId));
    }
}
