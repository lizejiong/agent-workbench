package com.lzj.rag.backend.ingestion.config;

import jakarta.validation.constraints.*;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 入库工作线程、租约、重试退避和失效代次清理的配置。
 *
 * @param enabled 是否启用该资源或能力。
 * @param concurrency 允许同时执行的工作任务数量。
 * @param queueCapacity 工作线程池允许排队的任务数量。
 * @param maxAttempts 允许的最大执行尝试次数。
 * @param lease 单次任务领取的有效租约时长。
 * @param heartbeat 后台任务续租扫描间隔。
 * @param backoff 首次失败后的退避时长。
 * @param maxBackoff 指数退避允许的最大时长。
 * @param cleanupDelay 后台清理扫描或延迟回收的时间间隔。
 * @param cleanupMaxAttempts 源对象补偿清理的最大尝试次数。
 */
@Validated
@ConfigurationProperties("kb.worker")
public record IngestionWorkerProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("2") @Min(1) @Max(16) int concurrency,
        @DefaultValue("0") @Min(0) @Max(64) int queueCapacity,
        @DefaultValue("3") @Min(1) @Max(10) int maxAttempts,
        @DefaultValue("30s") Duration lease,
        @DefaultValue("5s") Duration heartbeat,
        @DefaultValue("2s") Duration backoff,
        @DefaultValue("5m") Duration maxBackoff,
        @DefaultValue("10m") Duration cleanupDelay,
        @DefaultValue("5") @Min(1) @Max(10) int cleanupMaxAttempts) {

    /** 校验并补齐缺省配置，构造可直接使用的配置快照。 */
    public IngestionWorkerProperties {
        if (lease != null
                && heartbeat != null
                && (lease.compareTo(Duration.ofSeconds(1)) < 0
                        || lease.compareTo(Duration.ofMinutes(5)) > 0
                        || heartbeat.isNegative()
                        || heartbeat.isZero()
                        || heartbeat.multipliedBy(3).compareTo(lease) > 0))
            throw new IllegalArgumentException(
                    "Worker heartbeat must be positive and at most one third of the 1s..5m lease");
        if (backoff != null
                && maxBackoff != null
                && (backoff.isNegative()
                        || backoff.isZero()
                        || maxBackoff.compareTo(backoff) < 0
                        || maxBackoff.compareTo(Duration.ofHours(1)) > 0))
            throw new IllegalArgumentException(
                    "Worker retry backoff must be positive and bounded by at most 1h");
        if (cleanupDelay != null
                && (cleanupDelay.isNegative() || cleanupDelay.compareTo(Duration.ofDays(7)) > 0))
            throw new IllegalArgumentException("Cleanup delay must be between zero and seven days");
    }

    /**
     * 按尝试次数计算指数退避，结果不超过配置的最大退避时长。
     *
     * @param attempt 当前执行尝试的序号。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public long backoffMillis(int attempt) {
        // base：当前操作涉及的知识库及其固定模型绑定。
        long base =
                Math.min(
                        maxBackoff.toMillis(),
                        backoff.toMillis() * (1L << Math.min(10, Math.max(0, attempt - 1))));
        return Math.min(
                maxBackoff.toMillis(),
                base
                        + java.util.concurrent.ThreadLocalRandom.current()
                                .nextLong(Math.max(1, base / 4)));
    }
}
