package com.lzj.rag.backend.chunk.config;

import jakarta.validation.constraints.*;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 人工维护及后台清理的容量、租约和重试限制。
 *
 * @param maxBatchIds 批量维护允许指定的切片数量上限。
 * @param modelDeadline 当前模型调用最终采用的截止时间。
 * @param cleanupLease 单次清理尝试的租约时长。
 */
@Validated
@ConfigurationProperties("kb.maintenance")
public record MaintenanceProperties(
        @DefaultValue("200") @Min(1) @Max(1000) int maxBatchIds,
        @DefaultValue("40s") Duration modelDeadline,
        @DefaultValue("4m") Duration cleanupLease) {

    /** 校验并补齐缺省配置，构造可直接使用的配置快照。 */
    public MaintenanceProperties {
        if (modelDeadline != null
                && (modelDeadline.isNegative()
                        || modelDeadline.isZero()
                        || modelDeadline.compareTo(Duration.ofMinutes(2)) > 0))
            throw new IllegalArgumentException(
                    "Maintenance model deadline must be positive and <=2m");
        if (cleanupLease != null
                && (cleanupLease.compareTo(Duration.ofSeconds(10)) < 0
                        || cleanupLease.compareTo(Duration.ofMinutes(10)) > 0))
            throw new IllegalArgumentException("Document cleanup lease must be 10s..10m");
    }
}
