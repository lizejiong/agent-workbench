package com.lzj.rag.ai.model.service.bo;

import java.util.List;

/**
 * 整组向量业务结果；全部批次通过校验后才会产生。
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
public record EmbedResultBO(
        List<EmbeddingVectorBO> embeddings,
        int dimensions,
        String model,
        UsageBO usage,
        MetricsBO metrics,
        String provider,
        String embeddingSpace,
        String configRevision) {}
