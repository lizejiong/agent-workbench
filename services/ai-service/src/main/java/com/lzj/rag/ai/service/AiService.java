package com.lzj.rag.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lzj.rag.ai.api.ApiTypes.*;
import com.lzj.rag.ai.api.ApiTypes.Vector;
import com.lzj.rag.ai.config.ModelRegistry;
import com.lzj.rag.ai.provider.ProviderClient;
import com.lzj.rag.ai.resilience.RequestGate;
import org.slf4j.*;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.*;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.function.Function;
import static com.lzj.rag.ai.config.AiProperties.*;

@Service
public class AiService {
    private static final Logger log = LoggerFactory.getLogger(AiService.class);
    private final ModelRegistry registry;
    private final ProviderClient provider;
    private final RequestGate gate;
    private final ObjectMapper json;
    public AiService(ModelRegistry registry, ProviderClient provider, RequestGate gate, ObjectMapper json) {
        this.registry = registry; this.provider = provider; this.gate = gate; this.json = json;
    }
    public Mono<ChatResult> chat(ChatRequest request, boolean vision, String requestId) {
        return Mono.defer(() -> {
            validateChat(request, vision);
            var candidates = registry.chat(request, vision); var run = new Run(requestId);
            return logical(run, () -> attempt(candidates, 0, run, alias -> provider.chat(alias, request, requestId).map(reply -> {
                validateJson(request, reply.content(), !reply.tools().isEmpty());
                return new ChatResult(reply.content(), reply.reasoning(), reply.tools(), alias, reply.finish(), reply.usage(), run.metrics());
            })));
        });
    }
    public Flux<ServerSentEvent<Event>> stream(ChatRequest request, boolean vision, String requestId) {
        return Flux.defer(() -> {
            validateChat(request, vision);
            var candidates = registry.chat(request, vision); var run = new Run(requestId);
            return Flux.using(gate::enter, permit -> streamAttempt(candidates, 0, run, request)
                    .onErrorResume(error -> run.visible ? Flux.just(event("error", run,
                            Map.of("code", AiException.classify(error).category(), "message", AiException.classify(error).getMessage()))) : Flux.error(error)),
                    AiService::close);
        });
    }
    private Flux<ServerSentEvent<Event>> streamAttempt(List<String> candidates, int index, Run run, ChatRequest request) {
        return Flux.defer(() -> {
            if (index >= candidates.size() || run.attempts >= registry.config().limits().maxAttempts())
                return Flux.error(run.last == null ? AiException.unavailable("No available model") : run.last);
            String alias = candidates.get(index);
            Duration budget = registry.model(alias).timeout() == null ? remaining(run) : min(remaining(run), registry.model(alias).timeout());
            RequestGate.Lease lease;
            try { lease = gate.acquire(alias); }
            catch (AiException open) { run.last = open; return streamAttempt(candidates, index + 1, run, request); }
            int attemptNumber = ++run.attempts; run.model = alias;
            AiException[] attemptError = {null};
            long attemptStart = System.nanoTime();
            StringBuilder content = new StringBuilder(); boolean[] tools = {false}; String[] finish = {null};
            var source = provider.stream(alias, request, run.id).skipUntil(Chunk::meaningful)
                    .timeout(Mono.delay(min(budget, registry.config().limits().firstTokenTimeout())), chunk -> Mono.never())
                    .switchOnFirst((signal, sameFlux) -> {
                        if (signal.hasError()) return Flux.error(signal.getThrowable());
                        if (!signal.hasValue()) return Flux.error(AiException.invalid());
                        run.firstTokenMs = run.elapsed(); run.visible = true;
                        return Flux.concat(Flux.just(event("start", run, Map.of("attempt", run.attempts))),
                                sameFlux.timeout(registry.config().limits().streamIdleTimeout()).handle((chunk, sink) -> {
                                    if (chunk.type().equals("finish")) { finish[0] = chunk.finishReason(); return; }
                                    if (chunk.type().equals("content") && Boolean.TRUE.equals(request.json())) content.append(chunk.text());
                                    if (chunk.type().equals("tool")) tools[0] = true;
                                    Object payload = switch (chunk.type()) {
                                        case "tool" -> chunk.tool();
                                        case "usage" -> chunk.usage();
                                        default -> Map.of("text", chunk.text());
                                    };
                                    sink.next(event(chunk.type(), run, payload));
                                }), Flux.defer(() -> {
                                    validateJson(request, content.toString(), tools[0]);
                                    return Flux.just(event("done", run, Map.of("finishReason", finish[0], "metrics", run.metrics())));
                                }));
                    }).takeUntilOther(Mono.delay(budget).flatMap(t -> Mono.error(AiException.timeout())));
            return source.doOnComplete(lease::success).doOnError(error -> {
                        run.last = AiException.classify(error); attemptError[0] = run.last; lease.failure(run.last.counted());
                    }).doFinally(signal -> { lease.close(); progress(run, alias, attemptNumber, attemptError[0], attemptStart, signal.name()); })
                    .onErrorResume(error -> {
                        AiException failure = AiException.classify(error);
                        if (run.visible || !failure.retryable()) return Flux.error(failure);
                        return streamAttempt(candidates, index + 1, run, request);
                    });
        });
    }
    public Mono<EmbedResult> embed(EmbedRequest request, String requestId) {
        return Mono.defer(() -> {
            if (request == null || (request.text() == null) == (request.texts() == null)) throw AiException.input("Use exactly one of text or texts");
            List<String> texts = request.texts() == null ? List.of(request.text()) : request.texts();
            validateTexts(texts);
            String anchor = request.model() == null ? registry.config().embedding().defaultModel() : request.model();
            var model = registry.model(anchor);
            int dimensions = request.dimensions() == null ? model.dimensions() == null ? 0 : model.dimensions() : request.dimensions();
            if (dimensions <= 0 || (model.allowedDimensions().isEmpty() ? !Objects.equals(model.dimensions(), dimensions)
                    : !model.allowedDimensions().contains(dimensions))) throw AiException.input("Unsupported embedding dimensions");
            var descriptor = registry.embeddingDescriptor(anchor, dimensions);
            if (request.expectedConfigRevision() != null && !request.expectedConfigRevision().equals(descriptor.configRevision()))
                throw new AiException(409, "MODEL_CONFIG_CHANGED", "Embedding configuration changed", false, false);
            List<String> candidates = registry.operation(anchor, request.model() != null || request.expectedConfigRevision() != null
                            ? List.of() : registry.config().embedding().fallbacks(), Capability.EMBEDDING)
                    .stream().filter(a -> {
                        var m = registry.model(a);
                        return Objects.equals(model.embeddingSpace(), m.embeddingSpace()) &&
                                (m.allowedDimensions().isEmpty() ? Objects.equals(m.dimensions(), dimensions) : m.allowedDimensions().contains(dimensions));
                    }).toList();
            registry.available(candidates); var run = new Run(requestId);
            return logical(run, () -> attempt(candidates, 0, run, alias -> {
                int size = registry.model(alias).batchSize(); List<Vector> all = new ArrayList<>(); List<Usage> usages = new ArrayList<>();
                return Flux.range(0, (texts.size() + size - 1) / size).concatMap(batch -> {
                    int offset = batch * size; List<String> group = texts.subList(offset, Math.min(texts.size(), offset + size));
                    return provider.embed(alias, group, dimensions, requestId).doOnNext(reply -> {
                        if (reply.vectors().size() != group.size()) throw AiException.invalid();
                        boolean[] seen = new boolean[group.size()];
                        for (var vector : reply.vectors()) {
                            int i = vector.index();
                            if (i < 0 || i >= group.size() || seen[i] || vector.values().size() != dimensions
                                    || vector.values().stream().anyMatch(v -> v == null || !Double.isFinite(v) || Math.abs(v) > Float.MAX_VALUE)
                                    || vector.values().stream().allMatch(v -> v == 0d)) throw AiException.invalid();
                            seen[i] = true; all.add(new Vector(offset + i, vector.values()));
                        }
                        usages.add(reply.usage());
                    });
                }).then(Mono.fromSupplier(() -> {
                    all.sort(Comparator.comparingInt(Vector::index));
                    var actual = registry.embeddingDescriptor(alias, dimensions);
                    return new EmbedResult(all, dimensions, alias, sum(usages), run.metrics(), actual.provider(), actual.embeddingSpace(), actual.configRevision());
                }));
            }));
        });
    }
    public Mono<RerankResult> rerank(RerankRequest request, String requestId) {
        return Mono.defer(() -> {
            if (request == null || request.query() == null || request.query().isBlank() || request.documents() == null)
                throw AiException.input("query and documents are required");
            List<String> texts = request.documents().stream().map(d -> {
                if (d == null || d.id() == null || d.id().isBlank()) throw AiException.input("Document id is required");
                return d.text();
            }).toList();
            validateTexts(texts);
            if ((long) request.query().length() + texts.stream().mapToLong(String::length).sum() > registry.config().limits().maxInputChars())
                throw AiException.input("Input is too large");
            int topN = request.topN() == null ? Math.min(5, texts.size()) : Math.min(request.topN(), texts.size());
            if (topN <= 0) throw AiException.input("topN must be positive");
            String anchor = request.model() == null ? registry.config().rerank().defaultModel() : request.model();
            var candidates = registry.available(registry.operation(anchor, registry.config().rerank().fallbacks(), Capability.RERANK));
            var run = new Run(requestId);
            return logical(run, () -> attempt(candidates, 0, run, alias -> provider.rerank(alias, request.query(), texts, topN, requestId).map(reply -> {
                Set<Integer> seen = new HashSet<>();
                for (var score : reply.scores()) if (score.index() < 0 || score.index() >= texts.size() || !seen.add(score.index())) throw AiException.invalid();
                if (reply.scores().size() < topN) throw AiException.invalid();
                var scores = reply.scores().stream().sorted(Comparator.comparingDouble(ProviderClient.Score::score).reversed()
                        .thenComparingInt(ProviderClient.Score::index)).limit(topN)
                        .map(s -> new Ranked(request.documents().get(s.index()).id(), s.index(), s.score())).toList();
                return new RerankResult(scores, alias, reply.usage(), run.metrics());
            })));
        });
    }
    private <T> Mono<T> logical(Run run, java.util.function.Supplier<Mono<T>> operation) {
        return Mono.using(gate::enter, permit -> Mono.defer(operation).timeout(remaining(run)), AiService::close)
                .onErrorMap(AiException::classify);
    }
    private <T> Mono<T> attempt(List<String> candidates, int index, Run run, Function<String, Mono<T>> call) {
        return Mono.defer(() -> {
            if (index >= candidates.size() || run.attempts >= registry.config().limits().maxAttempts())
                return Mono.error(run.last == null ? AiException.unavailable("No available model") : run.last);
            String alias = candidates.get(index);
            Duration timeout = registry.model(alias).timeout();
            Duration budget = timeout == null ? remaining(run) : min(timeout, remaining(run));
            RequestGate.Lease lease;
            try { lease = gate.acquire(alias); }
            catch (AiException open) { run.last = open; return attempt(candidates, index + 1, run, call); }
            int attemptNumber = ++run.attempts; run.model = alias; long start = System.nanoTime();
            AiException[] attemptError = {null};
            return Mono.defer(() -> call.apply(alias)).timeout(budget)
                    .doOnSuccess(value -> lease.success()).doOnError(error -> {
                        run.last = AiException.classify(error); attemptError[0] = run.last; lease.failure(run.last.counted());
                    }).doFinally(signal -> { lease.close(); progress(run, alias, attemptNumber, attemptError[0], start, signal.name()); })
                    .onErrorResume(error -> {
                        AiException failure = AiException.classify(error);
                        return failure.retryable() ? attempt(candidates, index + 1, run, call) : Mono.error(failure);
                    });
        });
    }
    private void validateChat(ChatRequest r, boolean vision) {
        if (r == null || (r.text() == null) == (r.messages() == null)) throw AiException.input("Use exactly one of text or messages");
        if (r.maxOutputTokens() != null && r.maxOutputTokens() <= 0) throw AiException.input("maxOutputTokens must be positive");
        if (r.text() != null) validateTexts(List.of(r.text()));
        int images = 0; long chars = 0;
        if (r.messages() != null) {
            if (r.messages().isEmpty() || r.messages().size() > registry.config().limits().maxInputs()) throw AiException.input("Invalid messages count");
            for (var m : r.messages()) {
                if (m == null || m.role() == null || !Set.of("system", "user", "assistant", "tool").contains(m.role())) throw AiException.input("Invalid message role");
                if ((m.content() == null || m.content().isBlank()) && (m.images() == null || m.images().isEmpty()) && (m.toolCalls() == null || m.toolCalls().isEmpty()))
                    throw AiException.input("Message must contain content, images or tool calls");
                chars += m.content() == null ? 0 : m.content().length(); chars += m.reasoning() == null ? 0 : m.reasoning().length();
                if (m.role().equals("tool") && (m.toolCallId() == null || m.toolCallId().isBlank())) throw AiException.input("Tool message requires toolCallId");
                if (m.toolCalls() != null) for (var c : m.toolCalls()) {
                    if (c == null || c.id() == null || c.id().isBlank() || c.name() == null || c.name().isBlank() || c.arguments() == null)
                        throw AiException.input("Incomplete tool call");
                    chars += c.arguments().length();
                }
                if (m.images() != null) for (var image : m.images()) { validateImage(image); images++; }
            }
        }
        if (chars > registry.config().limits().maxInputChars()) throw AiException.input("Input is too large");
        if (images > registry.config().limits().maxImages()) throw AiException.input("Too many images");
        if (vision && images == 0) throw AiException.input("Vision requires images");
        if (!vision && images > 0) throw AiException.input("Use the vision endpoint for images");
        if (r.tools() != null) {
            if (r.tools().size() > 64) throw AiException.input("Too many tools");
            Set<String> names = new HashSet<>();
            for (var t : r.tools()) if (t == null || t.name() == null || !t.name().matches("[A-Za-z0-9_-]{1,64}")
                    || !names.add(t.name()) || t.parameters() == null || !t.parameters().isObject()) throw AiException.input("Invalid tool definition");
        }
        if (r.toolChoice() != null && (r.tools() == null || r.tools().isEmpty())) throw AiException.input("toolChoice requires tools");
    }
    private void validateImage(Image image) {
        if (image == null || (image.url() == null) == (image.base64() == null)) throw AiException.input("Image requires URL or base64");
        if (image.url() != null) {
            try {
                var uri = URI.create(image.url());
                if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null || image.url().length() > 8192)
                    throw new IllegalArgumentException();
            } catch (RuntimeException e) { throw AiException.input("Invalid image URL"); }
        } else {
            if (image.mediaType() == null || !Set.of("image/png", "image/jpeg", "image/webp", "image/gif").contains(image.mediaType()))
                throw AiException.input("Unsupported image mediaType");
            int max = registry.config().limits().maxImageBytes();
            if (image.base64().length() > ((long) max + 2) / 3 * 4) throw AiException.input("Image is too large");
            try {
                byte[] bytes = Base64.getDecoder().decode(image.base64());
                if (bytes.length == 0 || bytes.length > max) throw new IllegalArgumentException();
            } catch (IllegalArgumentException e) { throw AiException.input("Invalid image base64 or size"); }
        }
    }
    private void validateTexts(List<String> texts) {
        var l = registry.config().limits();
        if (texts == null || texts.isEmpty() || texts.size() > l.maxInputs() || texts.stream().anyMatch(t -> t == null || t.isBlank()))
            throw AiException.input("Nonempty text inputs are required within configured count limit");
        if (texts.stream().mapToLong(String::length).sum() > l.maxInputChars()) throw AiException.input("Input is too large");
    }
    private void validateJson(ChatRequest request, String content, boolean tools) {
        if (!Boolean.TRUE.equals(request.json()) || (tools && (content == null || content.isEmpty()))) return;
        try (var parser = json.createParser(content == null ? "" : content)) {
            var node = json.readTree(parser);
            if (node == null || !node.isObject() || parser.nextToken() != null) throw AiException.invalid();
        }
        catch (java.io.IOException e) { throw AiException.invalid(); }
    }
    private Usage sum(List<Usage> usages) {
        if (usages.stream().anyMatch(Objects::isNull)) return null;
        return new Usage(sumField(usages.stream().map(Usage::inputTokens).toList()),
                sumField(usages.stream().map(Usage::outputTokens).toList()), sumField(usages.stream().map(Usage::totalTokens).toList()));
    }
    private Long sumField(List<Long> values) { return values.stream().anyMatch(Objects::isNull) ? null : values.stream().mapToLong(Long::longValue).sum(); }
    private ServerSentEvent<Event> event(String type, Run run, Object payload) {
        return ServerSentEvent.<Event>builder().event(type).data(new Event(run.id, run.model, payload)).build();
    }
    private Duration remaining(Run run) {
        long remaining = registry.config().limits().totalTimeout().toNanos() - (System.nanoTime() - run.start);
        if (remaining <= 0) throw AiException.timeout();
        return Duration.ofNanos(remaining);
    }
    private Duration min(Duration a, Duration b) { return a.compareTo(b) < 0 ? a : b; }
    private void progress(Run run, String alias, int attempt, AiException error, long start, String signal) {
        log.info("ai requestId={} model={} provider={} attempt={} errorCategory={} firstTokenMs={} durationMs={} signal={}",
                run.id, alias, registry.model(alias).provider(), attempt,
                signal.equals("ON_COMPLETE") ? "NONE" : signal.equals("CANCEL") ? "CANCELLED" : error == null ? "UNKNOWN" : error.category(),
                run.firstTokenMs, (System.nanoTime() - start) / 1_000_000, signal);
    }
    private static void close(AutoCloseable resource) { try { resource.close(); } catch (Exception e) { throw new IllegalStateException(e); } }
    private static final class Run {
        final String id; final long start = System.nanoTime();
        int attempts; String model; boolean visible; Long firstTokenMs; AiException last;
        Run(String id) { this.id = id; }
        long elapsed() { return (System.nanoTime() - start) / 1_000_000; }
        Metrics metrics() { return new Metrics(attempts, elapsed(), firstTokenMs); }
    }
}
