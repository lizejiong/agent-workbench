package com.lzj.rag.backend.model.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.lzj.rag.backend.knowledgebase.exception.KnowledgeBaseException;
import com.lzj.rag.backend.model.service.ChatClient;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.*;

@Component
public class AiChatClient implements ChatClient {
    private final AiEmbeddingClient transport;
    public AiChatClient(AiEmbeddingClient transport) { this.transport = transport; }

    public JsonNode chat(List<Map<String, String>> messages, String model, boolean structured,
                         int maxOutputTokens, String requestId, Instant deadline) {
        var response = transport.chatCall(Map.of("messages", messages, "preferredModel", model,
                "json", structured, "thinking", false, "maxOutputTokens", maxOutputTokens), requestId, deadline);
        if (!response.path("content").isTextual() || response.path("content").asText().isBlank()
                || !response.path("model").isTextual() || response.path("model").asText().isBlank()
                || !"stop".equals(response.path("finishReason").asText()))
            throw new KnowledgeBaseException(502, "RAG_MODEL_INVALID_RESPONSE", "Model response is incomplete or invalid");
        return response;
    }
}
