package com.lzj.rag.ai.api;

import com.lzj.rag.ai.api.ApiTypes.*;
import com.lzj.rag.ai.service.AiService;
import com.lzj.rag.ai.util.TextUtilities;
import com.lzj.rag.framework.convention.result.Result;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.*;

@RestController
@RequestMapping("/ai")
public class AiController {
    private final AiService service;
    public AiController(AiService service) { this.service = service; }
    @PostMapping("/chat")
    public Mono<Result<ChatResult>> chat(@RequestBody ChatRequest request, ServerWebExchange exchange) {
        return service.chat(request, false, id(exchange)).map(data -> result(data, exchange));
    }
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Event>> stream(@RequestBody ChatRequest request, ServerWebExchange exchange) {
        return service.stream(request, false, id(exchange));
    }
    @PostMapping("/vision")
    public Mono<Result<ChatResult>> vision(@RequestBody ChatRequest request, ServerWebExchange exchange) {
        return service.chat(request, true, id(exchange)).map(data -> result(data, exchange));
    }
    @PostMapping(value = "/vision/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Event>> visionStream(@RequestBody ChatRequest request, ServerWebExchange exchange) {
        return service.stream(request, true, id(exchange));
    }
    @PostMapping("/embed")
    public Mono<Result<EmbedResult>> embed(@RequestBody EmbedRequest request, ServerWebExchange exchange) {
        return service.embed(request, id(exchange)).map(data -> result(data, exchange));
    }
    @PostMapping("/rerank")
    public Mono<Result<RerankResult>> rerank(@RequestBody RerankRequest request, ServerWebExchange exchange) {
        return service.rerank(request, id(exchange)).map(data -> result(data, exchange));
    }
    @PostMapping("/utils/estimate-tokens")
    public Result<TokenEstimate> estimate(@RequestBody TextRequest request, ServerWebExchange exchange) {
        return result(TextUtilities.estimate(request.text()), exchange);
    }
    @PostMapping("/utils/strip-code-fence")
    public Result<TextRequest> strip(@RequestBody TextRequest request, ServerWebExchange exchange) {
        return result(new TextRequest(TextUtilities.stripFence(request.text())), exchange);
    }
    static String id(ServerWebExchange exchange) { return exchange.getAttribute(RequestIdFilter.KEY); }
    private <T> Result<T> result(T data, ServerWebExchange exchange) { return Result.success(data).setRequestId(id(exchange)); }
}
