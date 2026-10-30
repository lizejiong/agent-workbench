package com.lzj.rag.backend.model.config;

import jakarta.validation.constraints.*;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * AI 服务连接、调用预算及响应大小限制。
 *
 * @param baseUrl 外部服务基础地址。
 * @param token 当前任务的租约栅栏令牌或清理凭据。
 * @param connectTimeout 建立外部连接的时间预算。
 * @param callTimeout 单次 HTTP 调用的总时间预算。
 * @param concurrency 允许同时执行的工作任务数量。
 * @param batchSize 单批处理的元素数量。
 * @param maxBatchBytes 嵌入单批请求允许的最大编码字节数。
 * @param maxResponseBytes 单次外部调用允许接收的最大响应字节数。
 */
@Validated
@ConfigurationProperties("kb.ai")
public record AiClientProperties(
        @DefaultValue("http://127.0.0.1:8081") String baseUrl,
        String token,
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("30s") Duration callTimeout,
        @DefaultValue("2") @Min(1) @Max(64) int concurrency,
        @DefaultValue("10") @Min(1) @Max(128) int batchSize,
        @DefaultValue("65536") @Min(1024) @Max(1048576) int maxBatchBytes,
        @DefaultValue("4194304") @Min(1024) @Max(16777216) int maxResponseBytes) {}
