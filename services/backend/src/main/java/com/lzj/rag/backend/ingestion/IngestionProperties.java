package com.lzj.rag.backend.ingestion;

import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;
import java.time.Duration;

@Validated
@ConfigurationProperties("kb.ingestion")
public record IngestionProperties(@DefaultValue("120s") Duration deadline,
        @DefaultValue("1000000") @Min(1024) @Max(10000000) int maxParsedChars,
        @DefaultValue("1000") @Min(1) @Max(10000) int maxChunks,
        @DefaultValue("8192") @Min(1024) @Max(65536) int hardChunkChars,
        @DefaultValue("16384") @Min(1024) @Max(131072) int maxEmbeddingChars,
        @DefaultValue("100") @Min(1) @Max(500) int writeBatchSize) { }
