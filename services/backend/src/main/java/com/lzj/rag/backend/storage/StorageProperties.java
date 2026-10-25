package com.lzj.rag.backend.storage;

import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;
import java.time.Duration;

@Validated
@ConfigurationProperties("kb.storage")
public record StorageProperties(@DefaultValue("false") boolean enabled, String endpoint,
        @DefaultValue("us-east-1") @NotBlank String region, String bucket,
        @DefaultValue("false") boolean pathStyleAccess,
        @DefaultValue("20971520") @Min(1) @Max(1073741824) long maxUploadBytes,
        Path tempDirectory,
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("30s") Duration socketTimeout,
        @DefaultValue("40s") Duration attemptTimeout,
        @DefaultValue("90s") Duration apiTimeout,
        @DefaultValue("5m") Duration uploadLease,
        @DefaultValue("8") @Min(1) int maxConnections) { }
