package com.lzj.rag.backend.rag.config;

import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("kb.rag.context")
public record ContextProperties(
        @DefaultValue("8192") @Min(2048) @Max(131072) int windowTokens,
        @DefaultValue("1024") @Min(128) int systemReserve,
        @DefaultValue("1024") @Min(128) int outputReserve,
        @DefaultValue("512") @Min(128) int safetyReserve,
        @DefaultValue("5") @Min(1) @Max(50) int topK,
        @DefaultValue("2") @Min(1) @Max(50) int perDocument,
        @DefaultValue("3") @Min(1) @Max(50) int perQuery,
        @DefaultValue("2") @Min(1) @Max(50) int perSource,
        @DefaultValue("0") @Min(0) @Max(1) int neighborRadius) {
    public ContextProperties {
        if(systemReserve+outputReserve+safetyReserve>=windowTokens)
            throw new IllegalArgumentException("Context reserves exhaust model window");
    }
    public int inputBudget(){return windowTokens-systemReserve-outputReserve-safetyReserve;}
}
