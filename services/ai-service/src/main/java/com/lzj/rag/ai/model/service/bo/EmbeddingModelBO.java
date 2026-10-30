package com.lzj.rag.ai.model.service.bo;

import com.lzj.rag.ai.infra.provider.enums.ProviderKind;

/**
 * 可公开的嵌入模型描述，不包含密钥或内部连接地址。
 *
 * @param modelId 应用内部的逻辑模型别名。
 * @param provider 供应商配置标识，用于关联 ai.providers 中的连接配置。
 * @param providerKind 实际供应商所使用的原生协议类型。
 * @param modelName 供应商原生模型名称，与应用内部逻辑别名区分。
 * @param dimensions 本次向量结果使用的维度。
 * @param embeddingSpace 向量语义空间标识，仅相同空间和相同实际维度才允许兼容降级。
 * @param configVersion 部署方维护的嵌入语义版本，模型语义变化时必须更新。
 * @param configRevision 根据模型身份与维度计算出的配置修订摘要。
 * @param batchSize 该向量模型每次请求最多处理的文本条数。
 * @param active 本地配置是否启用且已有密钥，不代表真实账户探测成功。
 * @param protocolVersion 嵌入元数据与身份约定的协议版本。
 */
public record EmbeddingModelBO(
        String modelId,
        String provider,
        ProviderKind providerKind,
        String modelName,
        int dimensions,
        String embeddingSpace,
        String configVersion,
        String configRevision,
        int batchSize,
        boolean active,
        String protocolVersion) {}
