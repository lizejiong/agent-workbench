package com.lzj.rag.backend.rag.controller.request;

import jakarta.validation.constraints.*;
import java.util.*;

public record RagRetrieveRequest(
        @NotBlank @Size(max=16000) String query,
        @Size(max=64) List<@NotNull UUID> kbIds,
        @Size(min=1,max=500) List<@NotNull UUID> documentIds,
        @Min(1) @Max(200) Integer topK, Double minScore,
        @Pattern(regexp="AUTO|VECTOR|HYBRID|KEYWORD") String strategy,
        Boolean rerank, Double minRerankScore, UUID conversationId) {}
