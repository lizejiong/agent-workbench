package com.lzj.rag.backend.ingestion.config;

import jakarta.validation.constraints.*;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 入库流水线的时间预算、文本长度、切片数量和向量写入限制。
 *
 * @param deadline 本阶段或本次请求必须完成的截止时间。
 * @param maxParsedChars 单个文档解析后允许保留的字符数上限。
 * @param maxChunks 单个文档允许生成的最大切片数量。
 * @param hardChunkChars 单个展示切片的硬字符上限。
 * @param maxEmbeddingChars 单段嵌入文本的字符数上限。
 * @param writeBatchSize 向量写入时每批记录数量。
 */
@Validated
@ConfigurationProperties("kb.ingestion")
public record IngestionProperties(
        @DefaultValue("120s") Duration deadline,
        @DefaultValue("1000000") @Min(1024) @Max(10000000) int maxParsedChars,
        @DefaultValue("1000") @Min(1) @Max(10000) int maxChunks,
        @DefaultValue("8192") @Min(1024) @Max(65536) int hardChunkChars,
        @DefaultValue("16384") @Min(1024) @Max(131072) int maxEmbeddingChars,
        @DefaultValue("100") @Min(1) @Max(500) int writeBatchSize) {}
