package com.lzj.rag.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.time.Duration;
import java.util.*;

@ConfigurationProperties("ai")
public record AiProperties(@DefaultValue("true") boolean enabled,
        Map<String, Provider> providers, Map<String, Model> models, Map<String, Route> routes,
        @DefaultValue Embedding embedding, @DefaultValue Rerank rerank, @DefaultValue Limits limits) {
    public AiProperties {
        providers = providers == null ? Map.of() : providers;
        models = models == null ? Map.of() : models;
        routes = routes == null ? Map.of() : routes;
    }
    public enum Kind { BAILIAN, DEEPSEEK, SILICONFLOW }
    public enum Tier { FAST, STANDARD, STRONG }
    public enum Capability { CHAT, VISION, THINKING, TOOLS, JSON, EMBEDDING, RERANK }
    public record Provider(Kind kind, String baseUrl, String apiKey, String rerankUrl,
                           @DefaultValue("true") boolean enabled) { }
    public record Model(String provider, String model, @DefaultValue("true") boolean enabled,
                        Set<Capability> capabilities, @DefaultValue("8192") int maxOutputTokens,
                        Integer dimensions, List<Integer> allowedDimensions, String embeddingSpace,
                        @DefaultValue("32") int batchSize, Duration timeout) {
        public Model {
            capabilities = capabilities == null ? Set.of() : capabilities;
            allowedDimensions = allowedDimensions == null ? List.of() : allowedDimensions;
        }
    }
    public record Route(@DefaultValue("STANDARD") Tier defaultTier, Map<Tier, List<String>> candidates) {
        public Route { candidates = candidates == null ? Map.of() : candidates; }
    }
    public record Embedding(String defaultModel, List<String> fallbacks) {
        public Embedding { fallbacks = fallbacks == null ? List.of() : fallbacks; }
    }
    public record Rerank(String defaultModel, List<String> fallbacks) {
        public Rerank { fallbacks = fallbacks == null ? List.of() : fallbacks; }
    }
    public record Limits(@DefaultValue("3") int maxAttempts, @DefaultValue("32") int concurrency,
            @DefaultValue("90s") Duration totalTimeout, @DefaultValue("5s") Duration connectTimeout,
            @DefaultValue("20s") Duration firstTokenTimeout, @DefaultValue("20s") Duration streamIdleTimeout,
            @DefaultValue("3") int circuitFailures, @DefaultValue("30s") Duration circuitCooldown,
            @DefaultValue("4194304") int maxResponseBytes, @DefaultValue("1000000") int maxInputChars,
            @DefaultValue("128") int maxInputs, @DefaultValue("4") int maxImages,
            @DefaultValue("2097152") int maxImageBytes) { }
}
