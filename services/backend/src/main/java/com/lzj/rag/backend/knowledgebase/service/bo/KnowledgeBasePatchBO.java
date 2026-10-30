package com.lzj.rag.backend.knowledgebase.service.bo;

/**
 * 局部更新知识库名称、描述或启用状态的请求。
 *
 * @param version 当前记录版本或本次请求固定的版本标记。
 * @param name 当前业务资源或策略的公开名称。
 * @param description 资源描述或视觉模型生成的图片描述。
 * @param embeddingProfileId 后端公开的嵌入配置标识。
 */
public record KnowledgeBasePatchBO(
        Long version, String name, String description, String embeddingProfileId) {}
