package com.lzj.rag.framework.starter.web.filter;

import com.lzj.rag.framework.starter.web.context.ReactiveRequestIdContext;
import com.lzj.rag.framework.starter.web.context.RequestIdContext;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.core.scheduler.Schedulers;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.assertThat;

class ReactiveRequestIdFilterTest {
    private final ReactiveRequestIdFilter filter = new ReactiveRequestIdFilter();

    @Test
    void propagatesIncomingIdAcrossThreadSwitchesWithoutThreadLocalState() {
        var exchange = exchange("upstream-1");
        filter.filter(exchange, request -> Mono.just(true).publishOn(Schedulers.parallel())
                .then(ReactiveRequestIdContext.current()).doOnNext(id -> {
                    assertThat(id).isEqualTo("upstream-1");
                    assertThat(ReactiveRequestIdContext.get(request)).isEqualTo(id);
                    assertThat(request.getResponse().getHeaders().getFirst("X-Request-Id")).isEqualTo(id);
                    assertThat(RequestIdContext.get()).isNull();
                }).then()).block();
        assertThat(ReactiveRequestIdContext.current().block()).isNull();
    }

    @Test
    void replacesMissingInvalidAndOversizedIds() {
        for (String incoming : new String[]{null, "", "invalid id", "x".repeat(129)}) {
            var exchange = exchange(incoming);
            filter.filter(exchange, request -> ReactiveRequestIdContext.current().doOnNext(id -> {
                assertThat(id).matches("[a-f0-9]{32}");
                assertThat(request.getResponse().getHeaders().getFirst("X-Request-Id")).isEqualTo(id);
            }).then()).block();
        }
    }

    @Test
    void concurrentRequestsKeepSeparateContexts() {
        Map<String, String> identities = new ConcurrentHashMap<>();
        Flux.just("request-a", "request-b").flatMap(incoming -> filter.filter(exchange(incoming), request ->
                Mono.delay(Duration.ofMillis(10)).then(ReactiveRequestIdContext.current())
                        .doOnNext(id -> identities.put(incoming, id)).then())).blockLast();
        assertThat(identities).containsExactlyInAnyOrderEntriesOf(Map.of("request-a", "request-a", "request-b", "request-b"));
    }

    @Test
    void cancellationReachesTheHandler() {
        AtomicBoolean cancelled = new AtomicBoolean();
        var subscription = filter.filter(exchange("cancelled"), request -> Mono.<Void>never()
                .doFinally(signal -> cancelled.set(signal == SignalType.CANCEL))).subscribe();
        subscription.dispose();
        assertThat(cancelled).isTrue();
        assertThat(ReactiveRequestIdContext.current().block()).isNull();
    }

    private MockServerWebExchange exchange(String id) {
        var request = MockServerHttpRequest.get("/");
        if (id != null) request.header("X-Request-Id", id);
        return MockServerWebExchange.from(request.build());
    }
}
