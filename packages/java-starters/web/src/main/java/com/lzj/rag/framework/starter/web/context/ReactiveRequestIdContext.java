package com.lzj.rag.framework.starter.web.context;

import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Request identity for WebFlux; Reactor context carries it across thread switches. */
public final class ReactiveRequestIdContext {
    public static final String KEY = ReactiveRequestIdContext.class.getName() + ".requestId";
    public static final String HEADER_NAME = "X-Request-Id";

    private ReactiveRequestIdContext() { }

    public static String get(ServerWebExchange exchange) {
        return exchange.getAttribute(KEY);
    }

    public static Mono<String> current() {
        return Mono.deferContextual(context -> Mono.justOrEmpty(context.<String>getOrEmpty(KEY)));
    }
}
