package com.lzj.rag.ai.model.controller.vo;

import java.util.List;

/**
 * 向量接口返回对象，包含有序向量和实际使用的向量身份。
 *
 * @param embeddings 按原始输入位置组织的完整向量结果。
 * @param dimensions 本次向量结果使用的维度。
 * @param model 本次实际使用或请求固定的逻辑模型别名。
 * @param usage 供应商报告的真实用量；缺失时保持 null。
 * @param metrics 一次逻辑请求的尝试次数和耗时统计。
 * @param provider 供应商配置标识，用于关联 ai.providers 中的连接配置。
 * @param embeddingSpace 向量语义空间标识，仅相同空间和相同实际维度才允许兼容降级。
 * @param configRevision 根据模型身份与维度计算出的配置修订摘要。
 */
public record EmbedVO(
        List<VectorVO> embeddings,
        int dimensions,
        String model,
        UsageVO usage,
        MetricsVO metrics,
        String provider,
        String embeddingSpace,
        String configRevision) {

    /**
     * 带输入位置的向量；index 用于恢复原始输入顺序。
     *
     * @param index 结果在当前输入列表中的零起始位置，用于恢复原顺序。
     * @param values 当前向量各维度的有序数值。
     */
    public record VectorVO(int index, List<Double> values) {}
}
