package com.lzj.rag.backend.knowledgebase.service.bo;

/**
 * 新建知识库的基本信息与嵌入配置选择。
 *
 * @param name 当前业务资源或策略的公开名称。
 * @param description 资源描述或视觉模型生成的图片描述。
 * @param embeddingProfileId 后端公开的嵌入配置标识。
 */
public record KnowledgeBaseCreateBO(String name, String description, String embeddingProfileId) {}
