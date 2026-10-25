package com.lzj.rag.ai.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.lzj.rag.ai.config.AiProperties.Tier;
import java.util.List;

public final class ApiTypes {
    private ApiTypes() { }
    public record ChatRequest(String text, List<Message> messages, String task, Tier tier,
            String preferredModel, Boolean thinking, Boolean json, Integer maxOutputTokens,
            List<Tool> tools, JsonNode toolChoice) { }
    public record Message(String role, String content, List<Image> images, String toolCallId,
                          List<ToolCall> toolCalls, String reasoning) { }
    public record Image(String url, String base64, String mediaType) { }
    public record Tool(String name, String description, JsonNode parameters) { }
    public record ToolCall(String id, String name, String arguments, Integer index) { }
    public record Usage(Long inputTokens, Long outputTokens, Long totalTokens) { }
    public record Metrics(int attempts, long durationMs, Long firstTokenMs) { }
    public record ChatResult(String content, String reasoning, List<ToolCall> toolCalls,
                             String model, String finishReason, Usage usage, Metrics metrics) { }
    public record Event(String requestId, String model, Object payload) { }
    public record Chunk(String type, String text, ToolCall tool, Usage usage, String finishReason) {
        public boolean meaningful() {
            return ((type.equals("content") || type.equals("reasoning")) && text != null && !text.isEmpty())
                    || (tool != null && (nonempty(tool.id) || nonempty(tool.name) || nonempty(tool.arguments)));
        }
        private static boolean nonempty(String value) { return value != null && !value.isEmpty(); }
    }
    public record EmbedRequest(String text, List<String> texts, String model, Integer dimensions, String expectedConfigRevision) {
        public EmbedRequest(String text, List<String> texts, String model, Integer dimensions) { this(text, texts, model, dimensions, null); }
    }
    public record Vector(int index, List<Double> values) { }
    public record EmbedResult(List<Vector> embeddings, int dimensions, String model, Usage usage, Metrics metrics,
                              String provider, String embeddingSpace, String configRevision) { }
    public record Document(String id, String text) { }
    public record RerankRequest(String query, List<Document> documents, Integer topN, String model) { }
    public record Ranked(String chunkId, int index, double score) { }
    public record RerankResult(List<Ranked> results, String model, Usage usage, Metrics metrics) { }
    public record TextRequest(String text) { }
    public record TokenEstimate(long tokens, boolean estimated) { }
}
