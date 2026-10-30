package com.lzj.rag.backend.knowledgebase.controller.vo;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 知识库查询响应，包含公开模型身份、版本和文档计数。
 *
 * @param id 当前业务对象的唯一标识。
 * @param name 当前业务资源或策略的公开名称。
 * @param description 资源描述或视觉模型生成的图片描述。
 * @param version 当前记录版本或本次请求固定的版本标记。
 * @param createdAt 记录创建时间。
 * @param updatedAt 记录最近更新时间。
 * @param bindingStatus 知识库嵌入模型绑定状态。
 * @param embeddingProfileId 后端公开的嵌入配置标识。
 * @param embeddingModelId 固定嵌入模型的逻辑标识。
 * @param embeddingProvider 固定嵌入模型的供应商标识。
 * @param embeddingDimension 固定向量维度。
 * @param embeddingConfigRevision 后端嵌入配置的稳定修订摘要。
 * @param vectorNamespace 知识库固定绑定的向量命名空间。
 * @param embeddingAiConfigRevision 已固定的 AI 服务配置修订摘要。
 * @param documentCount 知识库内符合统计条件的文档数量。
 */
public record KnowledgeBaseVO(
        UUID id,
        String name,
        String description,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        String bindingStatus,
        String embeddingProfileId,
        String embeddingModelId,
        String embeddingProvider,
        Integer embeddingDimension,
        String embeddingConfigRevision,
        String vectorNamespace,
        String embeddingAiConfigRevision,
        long documentCount) {}
