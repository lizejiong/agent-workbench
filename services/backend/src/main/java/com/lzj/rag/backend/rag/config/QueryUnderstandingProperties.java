package com.lzj.rag.backend.rag.config;

import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;
import java.time.Duration;
import java.util.Map;

@Validated
@ConfigurationProperties("kb.rag.understanding")
public record QueryUnderstandingProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("qwen-plus") @Pattern(regexp="[a-zA-Z0-9_.-]{1,128}") String model,
        @DefaultValue("5s") Duration timeout,
        @DefaultValue("3") @Min(1) @Max(3) int maxSubQueries,
        @DefaultValue("512") @Min(64) @Max(2048) int maxOutputTokens,
        Map<String, String> terms) {
    public QueryUnderstandingProperties {
        terms = terms == null ? Map.of() : Map.copyOf(terms);
        if (timeout == null || timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofSeconds(30)) > 0
                || terms.size() > 1000 || terms.entrySet().stream().anyMatch(e -> e.getKey().isBlank()
                || e.getValue().isBlank() || e.getKey().length() > 128 || e.getValue().length() > 256
                || e.getKey().matches(".*[0-9].*") && !e.getValue().contains(e.getKey())))
            throw new IllegalArgumentException("Invalid query understanding limits or term mapping");
    }
}
