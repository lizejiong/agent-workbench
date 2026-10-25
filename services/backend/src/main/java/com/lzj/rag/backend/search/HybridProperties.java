package com.lzj.rag.backend.search;
import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;
import java.time.Duration;

@Validated @ConfigurationProperties("kb.retrieval")
public record HybridProperties(@DefaultValue("AUTO") @Pattern(regexp="AUTO|VECTOR|HYBRID|KEYWORD") String strategy,
        @DefaultValue("50") @Min(1) @Max(200) int channelCandidates,
        @DefaultValue("20") @Min(1) @Max(200) int fusionCandidates,
        @DefaultValue("60") @Min(1) @Max(1000) int rrfConstant,
        @DefaultValue("1") @DecimalMin("0.01") @DecimalMax("100") double vectorWeight,
        @DefaultValue("1") @DecimalMin("0.01") @DecimalMax("100") double keywordWeight,
        @DefaultValue("4") @Min(2) @Max(32) int concurrency,
        @DefaultValue("40s") Duration vectorTimeout,@DefaultValue("5s") Duration keywordTimeout,
        @DefaultValue Rerank rerank) {
    public HybridProperties {
        if(fusionCandidates>channelCandidates || !Double.isFinite(vectorWeight) || !Double.isFinite(keywordWeight))throw new IllegalArgumentException("Funnel requires fusionCandidates<=channelCandidates and finite positive weights");
        for(var timeout:new Duration[]{vectorTimeout,keywordTimeout})if(timeout!=null && (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofMinutes(2))>0))throw new IllegalArgumentException("Channel timeouts must be positive and <=2m");
    }
    public record Rerank(@DefaultValue("false") boolean enabled,@DefaultValue("qwen-rerank") @Pattern(regexp="[a-zA-Z0-9_.-]{1,128}") String model,
            @DefaultValue("10s") Duration timeout,@DefaultValue("30000") @Min(1024) @Max(1000000) int maxInputChars,Double minScore) {
        public Rerank {if(timeout!=null && (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofMinutes(1))>0))throw new IllegalArgumentException("Rerank timeout must be positive and <=1m");if(minScore!=null && (!enabled || !Double.isFinite(minScore)))throw new IllegalArgumentException("A finite default rerank threshold requires enabled reranking");}
    }
}
