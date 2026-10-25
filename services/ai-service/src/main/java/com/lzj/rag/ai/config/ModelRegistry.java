package com.lzj.rag.ai.config;

import com.lzj.rag.ai.api.ApiTypes.ChatRequest;
import com.lzj.rag.ai.service.AiException;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import static com.lzj.rag.ai.config.AiProperties.*;

@Component
public class ModelRegistry {
    private final AiProperties config;
    public ModelRegistry(AiProperties config) {
        this.config = config;
        var l = config.limits();
        require(l.maxAttempts() > 0 && l.concurrency() > 0 && l.circuitFailures() > 0
                && l.maxResponseBytes() > 0 && l.maxInputChars() > 0 && l.maxInputs() > 0
                && l.maxImages() > 0 && l.maxImageBytes() > 0, "AI limits must be positive");
        for (var d : List.of(l.totalTimeout(), l.connectTimeout(), l.firstTokenTimeout(),
                l.streamIdleTimeout(), l.circuitCooldown())) positive(d);
        config.providers().forEach((id, p) -> {
            require(p.kind() != null, "Provider kind required: " + id); url(p.baseUrl());
            if (p.rerankUrl() != null) url(p.rerankUrl());
        });
        config.models().forEach((id, m) -> {
            require(config.providers().containsKey(m.provider()), "Unknown model provider: " + id);
            require(m.model() != null && !m.model().isBlank() && !m.capabilities().isEmpty(), "Invalid model: " + id);
            require(m.maxOutputTokens() > 0 && m.batchSize() > 0, "Invalid model limits: " + id);
            if (m.timeout() != null) positive(m.timeout());
            if (m.capabilities().contains(Capability.EMBEDDING)) {
                require(m.configVersion()!=null && !m.configVersion().isBlank() && m.configVersion().length()<=256,"Embedding configVersion required: "+id);
                require(m.dimensions() != null && m.dimensions() > 0 && m.embeddingSpace() != null
                        && !m.embeddingSpace().isBlank(), "Embedding identity required: " + id);
                require(m.allowedDimensions().stream().allMatch(d -> d != null && d > 0), "Invalid dimensions: " + id);
                require(m.allowedDimensions().isEmpty() || m.allowedDimensions().contains(m.dimensions()), "Default dimensions not allowed: " + id);
            }
            if (m.capabilities().contains(Capability.RERANK)) {
                var p = config.providers().get(m.provider());
                require(p.kind() != Kind.DEEPSEEK && p.rerankUrl() != null, "Unsupported rerank mapping: " + id);
            }
        });
        config.routes().forEach((task, r) -> {
            require(!task.isBlank() && r.defaultTier() != null, "Invalid task route");
            r.candidates().values().forEach(this::references);
        });
        reference(config.embedding().defaultModel()); references(config.embedding().fallbacks());
        reference(config.rerank().defaultModel()); references(config.rerank().fallbacks());
        require(model(config.embedding().defaultModel()).capabilities().contains(Capability.EMBEDDING), "Default embedding model lacks capability");
        require(model(config.rerank().defaultModel()).capabilities().contains(Capability.RERANK), "Default rerank model lacks capability");
        if (config.enabled()) {
            require(config.routes().containsKey("chat"), "Chat route required");
            for (Tier tier : Tier.values()) require(!route("chat", tier, null, Set.of(Capability.CHAT)).isEmpty(),
                    "No active CHAT candidate for " + tier + "; configure a key or AI_ENABLED=false");
        }
    }
    public AiProperties config() { return config; }
    public Model model(String alias) {
        var m = config.models().get(alias);
        if (m == null) throw AiException.input("Unknown logical model");
        return m;
    }
    public Provider provider(String alias) { return config.providers().get(model(alias).provider()); }
    public record EmbeddingDescriptor(String modelId, String provider, Kind providerKind, String modelName,
            int dimensions, String embeddingSpace, String configVersion, String configRevision, int batchSize,
            boolean active, String protocolVersion) { }
    public EmbeddingDescriptor embeddingDescriptor(String alias, Integer requestedDimensions) {
        var m = model(alias);
        if (!m.capabilities().contains(Capability.EMBEDDING)) throw AiException.input("Model lacks embedding capability");
        int dimension = requestedDimensions == null ? m.dimensions() : requestedDimensions;
        if (dimension <= 0 || (m.allowedDimensions().isEmpty() ? !Objects.equals(m.dimensions(), dimension) : !m.allowedDimensions().contains(dimension)))
            throw AiException.input("Unsupported embedding dimensions");
        try {
            // Length-prefixed identity excludes credentials/URLs; deployments bump configVersion for semantic changes.
            var bytes = new java.io.ByteArrayOutputStream();
            try (var out = new java.io.DataOutputStream(bytes)) {
                for (String value : List.of(alias, m.provider(), provider(alias).kind().name(), m.model(),
                        Integer.toString(dimension), m.embeddingSpace(), m.configVersion())) out.writeUTF(value);
            }
            String revision = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
            return new EmbeddingDescriptor(alias, m.provider(), provider(alias).kind(), m.model(), dimension, m.embeddingSpace(),
                    m.configVersion(), revision, m.batchSize(), config.enabled() && active(alias), "embed-v1");
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    public boolean active(String alias) {
        var m = model(alias); var p = provider(alias);
        return m.enabled() && p.enabled() && p.apiKey() != null && !p.apiKey().isBlank();
    }
    public List<String> chat(ChatRequest request, boolean vision) {
        enabled();
        Set<Capability> needed = EnumSet.of(Capability.CHAT);
        if (vision) needed.add(Capability.VISION);
        if (Boolean.TRUE.equals(request.thinking())) needed.add(Capability.THINKING);
        if (Boolean.TRUE.equals(request.json())) needed.add(Capability.JSON);
        if (request.tools() != null && !request.tools().isEmpty()) needed.add(Capability.TOOLS);
        if (request.messages() != null && request.messages().stream().anyMatch(m -> m.toolCallId() != null
                || (m.toolCalls() != null && !m.toolCalls().isEmpty()))) needed.add(Capability.TOOLS);
        return available(route(request.task() == null ? (vision ? "vision" : "chat") : request.task(),
                request.tier(), request.preferredModel(), needed));
    }
    public List<String> route(String task, Tier tier, String preferred, Set<Capability> needed) {
        var route = config.routes().get(task);
        if (route == null) throw AiException.input("Unknown task");
        LinkedHashSet<String> aliases = new LinkedHashSet<>();
        if (preferred != null) {
            if (!model(preferred).capabilities().containsAll(needed)) throw AiException.input("Preferred model lacks required capability");
            aliases.add(preferred);
        }
        aliases.addAll(route.candidates().getOrDefault(tier == null ? route.defaultTier() : tier, List.of()));
        return aliases.stream().filter(a -> active(a) && model(a).capabilities().containsAll(needed)).toList();
    }
    public List<String> operation(String anchor, List<String> fallback, Capability capability) {
        enabled(); model(anchor);
        if (!model(anchor).capabilities().contains(capability)) throw AiException.input("Model lacks required capability");
        LinkedHashSet<String> result = new LinkedHashSet<>(); result.add(anchor); result.addAll(fallback);
        return result.stream().filter(a -> active(a) && model(a).capabilities().contains(capability)).toList();
    }
    public List<String> available(List<String> candidates) {
        if (candidates.isEmpty()) throw AiException.unavailable("No active compatible model configured");
        return candidates;
    }
    public void enabled() { if (!config.enabled()) throw AiException.unavailable("AI service is disabled"); }
    private void references(List<String> ids) { ids.forEach(this::reference); }
    private void reference(String id) { require(id != null && config.models().containsKey(id), "Unknown model reference: " + id); }
    private static void positive(Duration d) { require(d != null && !d.isNegative() && !d.isZero(), "Duration must be positive"); }
    private static void url(String value) {
        try {
            var uri = URI.create(value);
            require(Set.of("https", "http").contains(uri.getScheme()) && uri.getHost() != null
                    && uri.getUserInfo() == null && uri.getFragment() == null && uri.getQuery() == null, "Invalid provider URL");
        } catch (RuntimeException e) { throw new IllegalArgumentException("Invalid provider URL"); }
    }
    private static void require(boolean test, String message) { if (!test) throw new IllegalArgumentException(message); }
}
