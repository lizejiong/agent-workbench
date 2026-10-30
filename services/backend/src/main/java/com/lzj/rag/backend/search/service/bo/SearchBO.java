package com.lzj.rag.backend.search.service.bo;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.*;

/**
 * 检索输入，指定查询、授权范围、策略及可选重排阈值。
 *
 * @param query 检索查询文本或已生成的查询向量。
 * @param kbIds 本次检索或查询涉及的知识库标识集合。
 * @param documentIds 限定检索范围的文档标识集合。
 * @param topK 本次请求希望保留的最终证据条数。
 * @param minScore 仅适用于向量通道的最低相似度阈值。
 * @param strategy 本次实际执行的检索策略。
 * @param rerank 本次请求是否启用模型重排。
 * @param minRerankScore 模型重排分数的最低阈值，不能用融合分数替代。
 */
public record SearchBO(
        String query,
        List<UUID> kbIds,
        List<UUID> documentIds,
        Integer topK,
        Double minScore,
        String strategy,
        Boolean rerank,
        Double minRerankScore) {

    /**
     * 初始化当前组件，保存业务依赖及本次使用的配置。
     *
     * @param query 检索查询文本或已生成的查询向量。
     * @param kbIds 本次检索或查询涉及的知识库标识集合。
     * @param documentIds 限定检索范围的文档标识集合。
     * @param topK 本次请求希望保留的最终证据条数。
     * @param minScore 仅适用于向量通道的最低相似度阈值。
     */
    public SearchBO(
            String query, List<UUID> kbIds, List<UUID> documentIds, Integer topK, Double minScore) {
        this(query, kbIds, documentIds, topK, minScore, null, null, null);
    }

    /** 校验并补齐缺省配置，构造可直接使用的配置快照。 */
    public SearchBO {
        if (topK == null) topK = 5;
    }

    /**
     * 校验相似度阈值是否在允许范围内且为有限数值。
     *
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @JsonIgnore
    public boolean isMinScoreValid() {
        return minScore == null || (Double.isFinite(minScore) && minScore >= -1 && minScore <= 1);
    }
}
