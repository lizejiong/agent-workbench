package com.lzj.rag.ai.infra.provider.dto;

import java.util.List;

/**
 * 供应商一次重排调用的原始分数集合。
 *
 * @param scores 当前处理的文档索引与相关性分数列表。
 * @param usage 供应商报告的真实用量；缺失时保持 null。
 */
public record ProviderRankingDTO(List<ScoreDTO> scores, ProviderUsageDTO usage) {

    /**
     * 供应商返回的文档位置与相关性分数。
     *
     * @param index 结果在当前输入列表中的零起始位置，用于恢复原顺序。
     * @param score 当前文档的原始位置与相关性分数。
     */
    public record ScoreDTO(int index, double score) {}
}
