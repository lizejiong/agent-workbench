package com.lzj.rag.backend.knowledgebase.service.bo;

/**
 * 更新知识库信息的请求，携带预期版本。
 *
 * @param name 当前业务资源或策略的公开名称。
 * @param description 资源描述或视觉模型生成的图片描述。
 * @param version 当前记录版本或本次请求固定的版本标记。
 */
public record KnowledgeBaseUpdateBO(String name, String description, Long version) {}
