package com.lzj.rag.backend.search.config;

import com.lzj.rag.backend.model.catalog.EmbeddingCatalog;

import jakarta.validation.constraints.*;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;
import java.util.Set;

/**
 * 关键词检索开关、索引身份、分析器及同步工作线程配置。
 *
 * @param enabled 是否启用该资源或能力。
 * @param endpoint 外部服务连接地址。
 * @param apiKey 外部服务认证密钥，不写入日志或公开响应。
 * @param index 当前元素位置或关键词索引名称。
 * @param analyzer 关键词检索使用的分词分析器名称。
 * @param callTimeout 单次 HTTP 调用的总时间预算。
 * @param lease 单次任务领取的有效租约时长。
 * @param maxScopeDocuments 关键词同步或检索范围内允许处理的文档数量上限。
 * @param batchSize 单批处理的元素数量。
 * @param maxAttempts 允许的最大执行尝试次数。
 */
@Validated
@ConfigurationProperties("kb.keyword")
public record KeywordProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("http://localhost:9200") String endpoint,
        String apiKey,
        @DefaultValue("kb-chunks-v1") @Pattern(regexp = "[a-z][a-z0-9_-]{2,100}") String index,
        @DefaultValue("cjk") @Pattern(regexp = "cjk|smartcn|ik_max_word|ik_smart") String analyzer,
        @DefaultValue("3s") Duration callTimeout,
        @DefaultValue("1m") Duration lease,
        @DefaultValue("2000") @Min(1) @Max(20000) int maxScopeDocuments,
        @DefaultValue("100") @Min(1) @Max(500) int batchSize,
        @DefaultValue("5") @Min(1) @Max(10) int maxAttempts) {

    /** 校验并补齐缺省配置，构造可直接使用的配置快照。 */
    public KeywordProperties {
        if (enabled) {
            // uri：当前 XML 命名空间或外部请求 URI。
            var uri = URI.create(endpoint);
            if (!Set.of("http", "https").contains(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null
                    || !(uri.getPath().isEmpty() || uri.getPath().equals("/")))
                throw new IllegalArgumentException(
                        "Keyword endpoint must be a trusted HTTP(S) origin");
        }
        if (callTimeout != null
                && lease != null
                && (callTimeout.isNegative()
                        || callTimeout.isZero()
                        || callTimeout.compareTo(Duration.ofSeconds(15)) > 0
                        || lease.compareTo(Duration.ofSeconds(15)) < 0
                        || lease.compareTo(Duration.ofMinutes(5)) > 0))
            throw new IllegalArgumentException(
                    "Keyword call timeout <=15s, lease 15s..5m required");
    }

    /**
     * 生成关键词索引的公开身份标记，用于响应追踪。
     *
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public String target() {
        return EmbeddingCatalog.sha256(endpoint + "/" + index + "/" + analyzer + "/schema1");
    }
}
