package com.lzj.rag.ai.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.lzj.rag.ai.api.ApiTypes.*;
import com.lzj.rag.ai.api.ApiTypes.Vector;
import com.lzj.rag.ai.config.ModelRegistry;
import com.lzj.rag.ai.service.AiException;
import io.netty.channel.ChannelOption;
import org.slf4j.*;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.*;
import reactor.core.publisher.*;
import reactor.netty.http.client.HttpClient;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.lzj.rag.ai.config.AiProperties.*;

/** One bounded HTTP transport; only protocol mapping varies by provider kind. */
@Component
public class ProviderClient {
    private static final Logger log = LoggerFactory.getLogger(ProviderClient.class);
    private final ModelRegistry registry;
    private final ObjectMapper json;
    private final WebClient http;
    public ProviderClient(ModelRegistry registry, ObjectMapper json) {
        this.registry = registry; this.json = json;
        var limits = registry.config().limits();
        this.http = WebClient.builder().clientConnector(new ReactorClientHttpConnector(HttpClient.create()
                        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) Math.min(Integer.MAX_VALUE, limits.connectTimeout().toMillis()))))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(limits.maxResponseBytes())).build();
    }
    public record Chat(String content, String reasoning, List<ToolCall> tools, String finish, Usage usage) { }
    public record Embeddings(List<Vector> vectors, Usage usage) { }
    public record Score(int index, double score) { }
    public record Ranking(List<Score> scores, Usage usage) { }

    public Mono<Chat> chat(String alias, ChatRequest request, String requestId) {
        return post(alias, "/chat/completions", chatBody(alias, request, false), requestId).map(root -> {
            JsonNode choice = root.path("choices").path(0), message = choice.path("message");
            if (!message.isObject() || !choice.path("finish_reason").isTextual()) throw AiException.invalid();
            List<ToolCall> calls = new ArrayList<>();
            for (JsonNode call : message.path("tool_calls")) {
                ToolCall normalized = tool(call, calls.size());
                if (normalized.id() == null || normalized.id().isEmpty() || normalized.name() == null
                        || normalized.name().isEmpty() || normalized.arguments() == null) throw AiException.invalid();
                calls.add(normalized);
            }
            String content = optionalText(message, "content"), reasoning = optionalText(message, "reasoning_content");
            if ((content == null || content.isEmpty()) && (reasoning == null || reasoning.isEmpty()) && calls.isEmpty()) throw AiException.invalid();
            return new Chat(content, reasoning,
                    calls, choice.path("finish_reason").asText(), usage(root.path("usage")));
        });
    }
    public Flux<Chunk> stream(String alias, ChatRequest request, String requestId) {
        return Flux.defer(() -> {
            var finished = new AtomicBoolean();
            return request(alias, "/chat/completions", chatBody(alias, request, true), requestId)
                    .accept(MediaType.TEXT_EVENT_STREAM).bodyValue(chatBody(alias, request, true)).exchangeToFlux(response -> {
                        observe(response, alias, requestId);
                        if (response.statusCode().isError()) return response.releaseBody().thenMany(Flux.error(status(response.statusCode().value())));
                        return response.bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() { });
                    })
                    .filter(event -> event.data() != null && !event.data().isBlank())
                    .takeUntil(event -> "[DONE]".equals(event.data().trim()))
                    .filter(event -> !"[DONE]".equals(event.data().trim()))
                    .concatMapIterable(event -> chunks(parse(event.data()), finished))
                    .concatWith(Flux.defer(() -> finished.get() ? Flux.empty() : Flux.error(AiException.invalid())));
        });
    }
    public Mono<Embeddings> embed(String alias, List<String> texts, int dimensions, String requestId) {
        ObjectNode body = json.createObjectNode().put("model", registry.model(alias).model());
        body.set("input", json.valueToTree(texts));
        if (!registry.model(alias).allowedDimensions().isEmpty()) body.put("dimensions", dimensions);
        return post(alias, "/embeddings", body, requestId).map(root -> {
            if (!root.path("data").isArray()) throw AiException.invalid();
            List<Vector> vectors = new ArrayList<>();
            for (var item : root.path("data")) {
                if (!integer(item.path("index")) || !item.path("embedding").isArray()) throw AiException.invalid();
                List<Double> values = new ArrayList<>();
                for (var value : item.path("embedding")) {
                    if (!value.isNumber() || !Double.isFinite(value.doubleValue())) throw AiException.invalid();
                    values.add(value.doubleValue());
                }
                vectors.add(new Vector(item.path("index").intValue(), values));
            }
            return new Embeddings(vectors, usage(root.path("usage")));
        });
    }
    public Mono<Ranking> rerank(String alias, String query, List<String> documents, int topN, String requestId) {
        ObjectNode body = json.createObjectNode().put("model", registry.model(alias).model());
        boolean bailian = registry.provider(alias).kind() == Kind.BAILIAN;
        ObjectNode input = bailian ? body.putObject("input") : body;
        input.put("query", query); input.set("documents", json.valueToTree(documents));
        ObjectNode params = bailian ? body.putObject("parameters") : body;
        params.put("top_n", topN).put("return_documents", false);
        return post(alias, registry.provider(alias).rerankUrl(), body, requestId).map(root -> {
            JsonNode results = bailian ? root.path("output").path("results") : root.path("results");
            if (!results.isArray()) throw AiException.invalid();
            List<Score> scores = new ArrayList<>();
            for (var item : results) {
                if (!integer(item.path("index")) || !item.path("relevance_score").isNumber()
                        || !Double.isFinite(item.path("relevance_score").doubleValue())) throw AiException.invalid();
                scores.add(new Score(item.path("index").intValue(), item.path("relevance_score").doubleValue()));
            }
            return new Ranking(scores, usage(root.hasNonNull("usage") ? root.path("usage") : root.path("meta").path("tokens")));
        });
    }
    private WebClient.RequestBodySpec request(String alias, String path, ObjectNode body, String requestId) {
        var provider = registry.provider(alias);
        String url = path.startsWith("http") ? path : provider.baseUrl().replaceAll("/+$", "") + path;
        return http.post().uri(url).headers(h -> {
            h.setBearerAuth(provider.apiKey()); h.set("X-Request-Id", requestId);
            if (provider.kind() == Kind.SILICONFLOW) h.set("X-Trace-Id", requestId);
        }).contentType(MediaType.APPLICATION_JSON);
    }
    private Mono<JsonNode> post(String alias, String path, ObjectNode body, String requestId) {
        return request(alias, path, body, requestId).bodyValue(body).exchangeToMono(response -> {
            observe(response, alias, requestId);
            if (response.statusCode().isError()) return response.releaseBody().then(Mono.error(status(response.statusCode().value())));
            return response.bodyToMono(String.class).map(this::parse).switchIfEmpty(Mono.error(AiException.invalid()));
        });
    }
    private void observe(ClientResponse response, String alias, String requestId) {
        String upstreamId = response.headers().asHttpHeaders().getFirst("X-Request-Id");
        if (upstreamId == null) upstreamId = response.headers().asHttpHeaders().getFirst("X-DashScope-Request-Id");
        if (upstreamId != null && upstreamId.matches("[A-Za-z0-9._:-]{1,128}"))
            log.debug("ai upstream requestId={} upstreamRequestId={} model={} provider={}",
                    requestId, upstreamId, alias, registry.model(alias).provider());
    }
    private ObjectNode chatBody(String alias, ChatRequest request, boolean stream) {
        var model = registry.model(alias);
        ObjectNode body = json.createObjectNode().put("model", model.model()).put("stream", stream)
                .put("max_tokens", request.maxOutputTokens() == null ? model.maxOutputTokens() : Math.min(request.maxOutputTokens(), model.maxOutputTokens()));
        ArrayNode messages = body.putArray("messages");
        List<Message> source = request.messages() == null ? List.of(new Message("user", request.text(), null, null, null, null)) : request.messages();
        for (var message : source) {
            ObjectNode item = messages.addObject().put("role", message.role());
            if (message.images() != null && !message.images().isEmpty()) {
                ArrayNode parts = item.putArray("content");
                if (message.content() != null) parts.addObject().put("type", "text").put("text", message.content());
                for (var image : message.images()) {
                    String url = image.url() != null ? image.url() : "data:" + image.mediaType() + ";base64," + image.base64();
                    parts.addObject().put("type", "image_url").putObject("image_url").put("url", url);
                }
            } else if (message.content() != null) item.put("content", message.content()); else item.putNull("content");
            if (message.reasoning() != null) item.put("reasoning_content", message.reasoning());
            if (message.toolCallId() != null) item.put("tool_call_id", message.toolCallId());
            if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
                var calls = item.putArray("tool_calls");
                for (var call : message.toolCalls()) calls.addObject().put("id", call.id()).put("type", "function")
                        .putObject("function").put("name", call.name()).put("arguments", call.arguments());
            }
        }
        if (model.capabilities().contains(Capability.THINKING)) {
            if (registry.provider(alias).kind() == Kind.DEEPSEEK) body.putObject("thinking").put("type", Boolean.TRUE.equals(request.thinking()) ? "enabled" : "disabled");
            else body.put("enable_thinking", Boolean.TRUE.equals(request.thinking()));
        }
        if (Boolean.TRUE.equals(request.json())) body.putObject("response_format").put("type", "json_object");
        if (request.tools() != null && !request.tools().isEmpty()) {
            var tools = body.putArray("tools");
            for (var tool : request.tools()) {
                var fn = tools.addObject().put("type", "function").putObject("function").put("name", tool.name());
                if (tool.description() != null) fn.put("description", tool.description());
                fn.set("parameters", tool.parameters());
            }
        }
        if (request.toolChoice() != null) body.set("tool_choice", request.toolChoice());
        if (stream) body.putObject("stream_options").put("include_usage", true);
        return body;
    }
    private List<Chunk> chunks(JsonNode root, AtomicBoolean finished) {
        List<Chunk> result = new ArrayList<>();
        for (var choice : root.path("choices")) {
            if (choice.path("index").asInt(0) != 0) continue;
            var delta = choice.path("delta");
            String reasoning = optionalText(delta, "reasoning_content"), content = optionalText(delta, "content");
            if (reasoning != null && !reasoning.isEmpty()) result.add(new Chunk("reasoning", reasoning, null, null, null));
            if (content != null && !content.isEmpty()) result.add(new Chunk("content", content, null, null, null));
            for (var call : delta.path("tool_calls")) {
                if (!integer(call.path("index"))) throw AiException.invalid();
                result.add(new Chunk("tool", null, tool(call, call.path("index").intValue()), null, null));
            }
            if (choice.path("finish_reason").isTextual()) {
                finished.set(true); result.add(new Chunk("finish", null, null, null, choice.path("finish_reason").asText()));
            }
        }
        Usage usage = usage(root.path("usage"));
        if (usage != null) result.add(new Chunk("usage", null, null, usage, null));
        return result;
    }
    private ToolCall tool(JsonNode call, int index) {
        var fn = call.path("function");
        return new ToolCall(optionalText(call, "id"), optionalText(fn, "name"), optionalText(fn, "arguments"), index);
    }
    private JsonNode parse(String value) {
        try {
            JsonNode root = json.readTree(value);
            if (root == null || !root.isObject() || root.hasNonNull("error")) throw AiException.invalid();
            return root;
        } catch (JsonProcessingException e) { throw AiException.invalid(); }
    }
    private Usage usage(JsonNode node) {
        if (!node.isObject()) return null;
        return new Usage(number(node, "prompt_tokens", "input_tokens"), number(node, "completion_tokens", "output_tokens"),
                number(node, "total_tokens", "tokens"));
    }
    private Long number(JsonNode node, String first, String second) {
        JsonNode value = node.hasNonNull(first) ? node.path(first) : node.path(second);
        if (value.isMissingNode() || value.isNull()) return null;
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) throw AiException.invalid();
        return value.longValue();
    }
    private String optionalText(JsonNode node, String key) {
        JsonNode value = node.path(key);
        if (value.isNull() || value.isMissingNode()) return null;
        if (!value.isTextual()) throw AiException.invalid();
        return value.textValue();
    }
    private boolean integer(JsonNode value) { return value.isIntegralNumber() && value.canConvertToInt(); }
    private AiException status(int status) {
        if (status == 400 || status == 413 || status == 422) return new AiException(400, "UPSTREAM_INPUT", "Upstream rejected input or context size", false, false);
        if (status == 401 || status == 403) return new AiException(502, "AUTH", "Upstream authentication failed", true, false);
        if (status == 429) return new AiException(503, "RATE_LIMIT", "Upstream rate limited", true, true);
        return new AiException(502, "UPSTREAM", "Upstream request failed", status >= 500, status >= 500);
    }
}
