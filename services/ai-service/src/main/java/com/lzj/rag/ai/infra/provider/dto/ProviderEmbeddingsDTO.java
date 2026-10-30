package com.lzj.rag.ai.infra.provider.dto;

import java.util.List;

/**
 * 供应商单批向量结果，索引仍相对于当前批次。
 *
 * @param vectors 当前供应商批次中解析出的向量结果。
 * @param usage 供应商报告的真实用量；缺失时保持 null。
 */
public record ProviderEmbeddingsDTO(List<VectorDTO> vectors, ProviderUsageDTO usage) {

    /**
     * 带输入位置的向量；index 用于恢复原始输入顺序。
     *
     * @param index 结果在当前输入列表中的零起始位置，用于恢复原顺序。
     * @param values 当前向量各维度的有序数值。
     */
    public record VectorDTO(int index, List<Double> values) {}
}
