package com.lzj.rag.backend.rag.config;

import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;
import java.time.Duration;

@Validated
@ConfigurationProperties("kb.rag.answer")
public record AnswerProperties(@DefaultValue("true") boolean enabled,
        @DefaultValue("qwen-plus") @Pattern(regexp="[a-zA-Z0-9_.-]{1,128}") String model,
        @DefaultValue("60s") Duration timeout,@DefaultValue("1024") @Min(64) @Max(8192) int maxOutputTokens,
        @DefaultValue("true") boolean repairCitations,@DefaultValue("16384") @Min(256) @Max(262144) int maxAnswerChars) {
    public AnswerProperties {
        if(timeout==null || timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofMinutes(2))>0)
            throw new IllegalArgumentException("Answer timeout must be positive and <=2 minutes");
    }
}
