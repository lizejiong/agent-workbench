package com.lzj.rag.framework.starter.web.handler;

import com.lzj.rag.framework.convention.errorcode.BaseErrorCode;
import com.lzj.rag.framework.convention.errorcode.ErrorCode;
import com.lzj.rag.framework.convention.exception.ClientException;
import com.lzj.rag.framework.convention.exception.RemoteException;
import com.lzj.rag.framework.convention.exception.ServiceException;
import com.lzj.rag.framework.starter.web.config.ReactiveWebProperties;
import com.lzj.rag.framework.starter.web.exception.HttpStatusAware;
import com.lzj.rag.framework.starter.web.filter.ReactiveRequestIdFilter;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

class ReactiveGlobalExceptionHandlerTest {
    @Test
    void mapsSharedExceptionsToHttpStatusAndPreservesTheRequestId() {
        var client = client(new ReactiveWebProperties());
        get(client, "/client").expectStatus().isBadRequest().expectBody()
                .jsonPath("$.code").isEqualTo(BaseErrorCode.CLIENT_ERROR.code())
                .jsonPath("$.message").isEqualTo("invalid input")
                .jsonPath("$.requestId").isEqualTo("reactive-test");
        get(client, "/remote").expectStatus().isEqualTo(502).expectBody()
                .jsonPath("$.code").isEqualTo(BaseErrorCode.REMOTE_ERROR.code());
        get(client, "/service").expectStatus().isEqualTo(500).expectBody()
                .jsonPath("$.code").isEqualTo(BaseErrorCode.SERVICE_ERROR.code());
        get(client, "/explicit").expectStatus().isEqualTo(429).expectBody()
                .jsonPath("$.code").isEqualTo("RATE_LIMIT");
    }

    @Test
    void hidesUnexpectedDetailsAndMapsUnreadableBodiesToClientErrors() {
        var client = client(new ReactiveWebProperties());
        get(client, "/unexpected").expectStatus().isEqualTo(500).expectBody()
                .jsonPath("$.message").isEqualTo(BaseErrorCode.SERVICE_ERROR.message());
        client.post().uri("/body").contentType(MediaType.APPLICATION_JSON).bodyValue("{invalid-json")
                .exchange().expectStatus().isBadRequest().expectBody()
                .jsonPath("$.code").isEqualTo(BaseErrorCode.CLIENT_ERROR.code())
                .jsonPath("$.requestId").isNotEmpty();
    }

    @Test
    void applicationsCanKeepTheirPublishedInputAndInternalErrorContract() {
        var properties = new ReactiveWebProperties();
        properties.setInputCode("INVALID_INPUT");
        properties.setInputMessage("Malformed request");
        properties.setInternalCode("INTERNAL");
        properties.setInternalMessage("Service failed");
        var client = client(properties);
        client.post().uri("/body").contentType(MediaType.APPLICATION_JSON).bodyValue("{")
                .exchange().expectStatus().isBadRequest().expectBody()
                .jsonPath("$.code").isEqualTo("INVALID_INPUT")
                .jsonPath("$.message").isEqualTo("Malformed request");
        get(client, "/unexpected").expectStatus().isEqualTo(500).expectBody()
                .jsonPath("$.code").isEqualTo("INTERNAL")
                .jsonPath("$.message").isEqualTo("Service failed");
    }

    @Test
    void preservesFrameworkHttpStatusesWithoutExposingTheirReasons() {
        var properties = new ReactiveWebProperties();
        properties.setInputCode("INVALID_INPUT");
        properties.setInputMessage("Malformed request");
        properties.setInternalCode("INTERNAL");
        properties.setInternalMessage("Service failed");
        var client = client(properties);
        client.post().uri("/body").contentType(MediaType.TEXT_PLAIN).bodyValue("unsupported body")
                .header("X-Request-Id", "reactive-test").exchange()
                .expectStatus().isEqualTo(415)
                .expectHeader().valueEquals("X-Request-Id", "reactive-test").expectBody()
                .jsonPath("$.code").isEqualTo("INVALID_INPUT")
                .jsonPath("$.message").isEqualTo("Malformed request")
                .jsonPath("$.requestId").isEqualTo("reactive-test");
        get(client, "/missing").expectStatus().isNotFound().expectBody()
                .jsonPath("$.code").isEqualTo("INVALID_INPUT")
                .jsonPath("$.message").isEqualTo("Malformed request")
                .jsonPath("$.requestId").isEqualTo("reactive-test");
        get(client, "/unavailable").expectStatus().isEqualTo(503).expectBody()
                .jsonPath("$.code").isEqualTo("INTERNAL")
                .jsonPath("$.message").isEqualTo("Service failed")
                .jsonPath("$.requestId").isEqualTo("reactive-test");
    }

    private WebTestClient client(ReactiveWebProperties properties) {
        return WebTestClient.bindToController(new FailureController())
                .controllerAdvice(new ReactiveGlobalExceptionHandler(properties))
                .webFilter(new ReactiveRequestIdFilter()).build();
    }

    private WebTestClient.ResponseSpec get(WebTestClient client, String path) {
        return client.get().uri(path).header("X-Request-Id", "reactive-test").exchange()
                .expectHeader().valueEquals("X-Request-Id", "reactive-test");
    }

    @RestController
    static class FailureController {
        @GetMapping("/client") void client() { throw new ClientException("invalid input"); }
        @GetMapping("/remote") void remote() { throw new RemoteException("upstream failed"); }
        @GetMapping("/service") void service() { throw new ServiceException("service failed"); }
        @GetMapping("/unexpected") void unexpected() { throw new IllegalStateException("internal secret"); }
        @GetMapping("/explicit") void explicit() { throw new RateLimitException(); }
        @GetMapping("/missing") void missing() { throw new ResponseStatusException(HttpStatus.NOT_FOUND, "private resource details"); }
        @GetMapping("/unavailable") void unavailable() { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "private upstream details"); }
        @PostMapping("/body") Map<String, String> body(@RequestBody Map<String, String> body) { return body; }
    }

    private static class RateLimitException extends ServiceException implements HttpStatusAware {
        RateLimitException() { super("busy", new RateLimitCode()); }
        @Override public int httpStatus() { return 429; }
    }

    private record RateLimitCode() implements ErrorCode {
        @Override public String code() { return "RATE_LIMIT"; }
        @Override public String message() { return "busy"; }
    }
}
