package com.lzj.rag.backend.model;
import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;
import java.time.Duration;

@Validated
@ConfigurationProperties("kb.ai")
public record AiClientProperties(@DefaultValue("http://127.0.0.1:8081") String baseUrl,String token,
        @DefaultValue("5s") Duration connectTimeout,@DefaultValue("30s") Duration callTimeout,
        @DefaultValue("2") @Min(1) @Max(64) int concurrency,
        @DefaultValue("10") @Min(1) @Max(128) int batchSize,
        @DefaultValue("65536") @Min(1024) @Max(1048576) int maxBatchBytes,
        @DefaultValue("4194304") @Min(1024) @Max(16777216) int maxResponseBytes) { }
