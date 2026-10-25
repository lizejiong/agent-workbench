package com.lzj.rag.backend.ingestion;

import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;
import java.time.Duration;

@Validated
@ConfigurationProperties("kb.worker")
public record IngestionWorkerProperties(@DefaultValue("true") boolean enabled,
        @DefaultValue("2") @Min(1) @Max(16) int concurrency,
        @DefaultValue("0") @Min(0) @Max(64) int queueCapacity,
        @DefaultValue("3") @Min(1) @Max(10) int maxAttempts,
        @DefaultValue("30s") Duration lease,
        @DefaultValue("5s") Duration heartbeat,
        @DefaultValue("2s") Duration backoff,
        @DefaultValue("5m") Duration maxBackoff,
        @DefaultValue("10m") Duration cleanupDelay,
        @DefaultValue("5") @Min(1) @Max(10) int cleanupMaxAttempts) {
    public IngestionWorkerProperties {
        if(lease!=null && heartbeat!=null && (lease.compareTo(Duration.ofSeconds(1))<0 || lease.compareTo(Duration.ofMinutes(5))>0
                || heartbeat.isNegative() || heartbeat.isZero() || heartbeat.multipliedBy(3).compareTo(lease)>0))
            throw new IllegalArgumentException("Worker heartbeat must be positive and at most one third of the 1s..5m lease");
        if(backoff!=null && maxBackoff!=null && (backoff.isNegative() || backoff.isZero() || maxBackoff.compareTo(backoff)<0 || maxBackoff.compareTo(Duration.ofHours(1))>0))
            throw new IllegalArgumentException("Worker retry backoff must be positive and bounded by at most 1h");
        if(cleanupDelay!=null && (cleanupDelay.isNegative() || cleanupDelay.compareTo(Duration.ofDays(7))>0))
            throw new IllegalArgumentException("Cleanup delay must be between zero and seven days");
    }
    public long backoffMillis(int attempt) {
        long base=Math.min(maxBackoff.toMillis(),backoff.toMillis()*(1L<<Math.min(10,Math.max(0,attempt-1))));
        return Math.min(maxBackoff.toMillis(),base+java.util.concurrent.ThreadLocalRandom.current().nextLong(Math.max(1,base/4)));
    }
}
