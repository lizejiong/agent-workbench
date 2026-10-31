package com.lzj.rag.backend.search.config;

import jakarta.validation.constraints.*;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 混合检索的召回容量、RRF 权重、通道预算和重排配置。
 *
 * @param strategy 本次实际执行的检索策略。
 * @param channelCandidates 每个召回通道的候选数量。
 * @param fusionCandidates 融合后保留的候选数量。
 * @param rrfConstant RRF 排名分母中的平滑常数。
 * @param vectorWeight 向量通道的 RRF 权重。
 * @param keywordWeight 关键词通道的 RRF 权重。
 * @param concurrency 允许同时执行的工作任务数量。
 * @param vectorTimeout 向量召回通道的时间预算。
 * @param keywordTimeout 关键词召回通道的时间预算。
 * @param rerank 混合检索中的重排模型、预算及阈值配置。
 */
@Validated
@ConfigurationProperties("kb.retrieval")
public record HybridProperties(
        @DefaultValue("AUTO") @Pattern(regexp = "AUTO|VECTOR|HYBRID|KEYWORD") String strategy,
        @DefaultValue("50") @Min(1) @Max(200) int channelCandidates,
        @DefaultValue("20") @Min(1) @Max(200) int fusionCandidates,
        @DefaultValue("60") @Min(1) @Max(1000) int rrfConstant,
        @DefaultValue("1") @DecimalMin("0.01") @DecimalMax("100") double vectorWeight,
        @DefaultValue("1") @DecimalMin("0.01") @DecimalMax("100") double keywordWeight,
        @DefaultValue("4") @Min(2) @Max(32) int concurrency,
        @DefaultValue("40s") Duration vectorTimeout,
        @DefaultValue("5s") Duration keywordTimeout,
        @DefaultValue Rerank rerank,
        @DefaultValue("3") @Min(1) @Max(3) int maxSubQueries,
        @DefaultValue("300") @Min(1) @Max(1200) int maxTotalCandidates) {

    public HybridProperties(String strategy,int channelCandidates,int fusionCandidates,int rrfConstant,
                            double vectorWeight,double keywordWeight,int concurrency,Duration vectorTimeout,
                            Duration keywordTimeout,Rerank rerank) {
        this(strategy,channelCandidates,fusionCandidates,rrfConstant,vectorWeight,keywordWeight,concurrency,
                vectorTimeout,keywordTimeout,rerank,3,300);
    }

    /** 校验并补齐缺省配置，构造可直接使用的配置快照。 */
    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public HybridProperties {
        if (maxTotalCandidates < channelCandidates || maxTotalCandidates < fusionCandidates || fusionCandidates > channelCandidates
                || !Double.isFinite(vectorWeight)
                || !Double.isFinite(keywordWeight))
            throw new IllegalArgumentException(
                    "Funnel requires fusionCandidates<=channelCandidates and finite positive weights");
        // timeout：当前阶段允许使用的局部时间预算。
        for (var timeout : new Duration[] {vectorTimeout, keywordTimeout})
            if (timeout != null
                    && (timeout.isNegative()
                            || timeout.isZero()
                            || timeout.compareTo(Duration.ofMinutes(2)) > 0))
                throw new IllegalArgumentException("Channel timeouts must be positive and <=2m");
    }

    /**
     * 重排开关、模型、预算及默认分数阈值。
     *
     * @param enabled 是否启用该资源或能力。
     * @param model 当前固定的模型配置或 AI 返回的模型身份节点。
     * @param timeout 当前阶段允许使用的局部时间预算。
     * @param maxInputChars 模型或解析输入的字符数上限。
     * @param minScore 仅适用于向量通道的最低相似度阈值。
     */
    public enum RerankFailureMode { BEST_EFFORT, REQUIRED }

    public record Rerank(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("qwen-rerank") @Pattern(regexp = "[a-zA-Z0-9_.-]{1,128}") String model,
            @DefaultValue("10s") Duration timeout,
            @DefaultValue("30000") @Min(1024) @Max(1000000) int maxInputChars,
            Double minScore,
            @DefaultValue("BEST_EFFORT") RerankFailureMode failureMode) {

        public Rerank(boolean enabled,String model,Duration timeout,int maxInputChars,Double minScore) {
            this(enabled,model,timeout,maxInputChars,minScore,RerankFailureMode.BEST_EFFORT);
        }

        /** 校验并补齐缺省配置，构造可直接使用的配置快照。 */
        @org.springframework.boot.context.properties.bind.ConstructorBinding
        public Rerank {
            if(failureMode==null || !enabled && failureMode==RerankFailureMode.REQUIRED)
                throw new IllegalArgumentException("Required reranking must be enabled");
            if (timeout != null
                    && (timeout.isNegative()
                            || timeout.isZero()
                            || timeout.compareTo(Duration.ofMinutes(1)) > 0))
                throw new IllegalArgumentException("Rerank timeout must be positive and <=1m");
            if (minScore != null && (!enabled || !Double.isFinite(minScore)))
                throw new IllegalArgumentException(
                        "A finite default rerank threshold requires enabled reranking");
        }
    }
}
