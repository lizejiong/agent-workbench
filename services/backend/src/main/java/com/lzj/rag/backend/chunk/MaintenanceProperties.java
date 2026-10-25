package com.lzj.rag.backend.chunk;
import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;
import java.time.Duration;

@Validated @ConfigurationProperties("kb.maintenance")
public record MaintenanceProperties(@DefaultValue("200") @Min(1) @Max(1000) int maxBatchIds,
        @DefaultValue("40s") Duration modelDeadline,@DefaultValue("4m") Duration cleanupLease) {
    public MaintenanceProperties {
        if(modelDeadline!=null && (modelDeadline.isNegative() || modelDeadline.isZero() || modelDeadline.compareTo(Duration.ofMinutes(2))>0))
            throw new IllegalArgumentException("Maintenance model deadline must be positive and <=2m");
        if(cleanupLease!=null && (cleanupLease.compareTo(Duration.ofSeconds(10))<0 || cleanupLease.compareTo(Duration.ofMinutes(10))>0))
            throw new IllegalArgumentException("Document cleanup lease must be 10s..10m");
    }
}
