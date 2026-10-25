package com.lzj.rag.backend.parser;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;
import java.time.Duration;

@Validated @ConfigurationProperties("kb.formats")
public record FormatProperties(@DefaultValue("30s") Duration localTimeout,@DefaultValue("2") @Min(1) @Max(8) int concurrency,
        @DefaultValue("256") @Min(128) @Max(1024) int processHeapMb,
        @DefaultValue("20000") @Min(1) @Max(100000) int maxRows,@DefaultValue("128") @Min(1) @Max(1024) int maxColumns,
        @DefaultValue("32") @Min(1) @Max(100) int maxSheets,@DefaultValue("500") @Min(1) @Max(2000) int maxPages,
        @DefaultValue("16777216") @Min(1024) @Max(50000000) int maxImagePixels,
        @DefaultValue("2097152") @Min(1024) @Max(10000000) int maxImageBytes,
        @DefaultValue("8388608") @Min(65536) @Max(33554432) int maxResultBytes,
        @Valid @DefaultValue Mineru mineru,@Valid @DefaultValue Vlm vlm) {
    public record Mineru(@DefaultValue("false") boolean enabled,@DefaultValue("") String baseUrl,@DefaultValue("") String token,
            @DefaultValue("mineru-2.7.6-file-parse") String protocol,@DefaultValue("90s") Duration timeout,
            @DefaultValue("pipeline") String backend,@DefaultValue("ch") String language,
            @DefaultValue("5s") Duration connectTimeout,@DefaultValue("1") @Min(1) @Max(8) int concurrency) { }
    public record Vlm(@DefaultValue("false") boolean enabled,@DefaultValue("qwen-vision") @NotBlank @Pattern(regexp="[A-Za-z0-9._-]{1,128}") String model,
            @DefaultValue("2048") @Min(64) @Max(8192) int maxOutputTokens) { }
    public FormatProperties {
        if(localTimeout!=null && (localTimeout.isNegative() || localTimeout.isZero() || localTimeout.compareTo(Duration.ofMinutes(2))>0))
            throw new IllegalArgumentException("Local parser timeout must be positive and <=2m");
    }
}
