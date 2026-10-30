package com.lzj.rag.backend.search.config;

import jakarta.validation.constraints.*;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 检索范围、查询长度、返回数量和总时间预算的限制。
 *
 * @param maxTopK 允许返回的最大检索条数。
 * @param maxKbIds 单次检索允许指定的知识库数量上限。
 * @param maxDocumentIds 单次检索允许指定的文档数量上限。
 * @param maxQueryChars 检索查询的字符数上限。
 * @param deadline 本阶段或本次请求必须完成的截止时间。
 */
@Validated
@ConfigurationProperties("kb.search")
public record SearchProperties(
        @DefaultValue("50") @Min(5) @Max(200) int maxTopK,
        @DefaultValue("16") @Min(1) @Max(64) int maxKbIds,
        @DefaultValue("100") @Min(1) @Max(500) int maxDocumentIds,
        @DefaultValue("4000") @Min(1) @Max(16000) int maxQueryChars,
        @DefaultValue("40s") Duration deadline) {

    /** 校验并补齐缺省配置，构造可直接使用的配置快照。 */
    public SearchProperties {
        if (deadline != null
                && (deadline.isNegative()
                        || deadline.isZero()
                        || deadline.compareTo(Duration.ofMinutes(2)) > 0))
            throw new IllegalArgumentException(
                    "Retrieval total deadline must be positive and at most 2 minutes");
    }
}
