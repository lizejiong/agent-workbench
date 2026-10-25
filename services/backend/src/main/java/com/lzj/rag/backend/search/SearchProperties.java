package com.lzj.rag.backend.search;

import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;
import java.time.Duration;

@Validated
@ConfigurationProperties("kb.search")
public record SearchProperties(@DefaultValue("50") @Min(5) @Max(200) int maxTopK,
        @DefaultValue("16") @Min(1) @Max(64) int maxKbIds,
        @DefaultValue("100") @Min(1) @Max(500) int maxDocumentIds,
        @DefaultValue("4000") @Min(1) @Max(16000) int maxQueryChars,
        @DefaultValue("40s") Duration deadline) {
    public SearchProperties {
        if(deadline!=null && (deadline.isNegative() || deadline.isZero() || deadline.compareTo(Duration.ofMinutes(2))>0))
            throw new IllegalArgumentException("Search model deadline must be positive and at most 2 minutes");
    }
}
