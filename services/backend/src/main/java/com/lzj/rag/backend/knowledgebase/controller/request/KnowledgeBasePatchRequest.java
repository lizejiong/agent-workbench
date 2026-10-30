package com.lzj.rag.backend.knowledgebase.controller.request;

import jakarta.validation.constraints.*;

/**
 * 局部更新知识库名称、描述或启用状态的请求。
 *
 * @param version 当前记录版本或本次请求固定的版本标记。
 * @param name 当前业务资源或策略的公开名称。
 * @param description 资源描述或视觉模型生成的图片描述。
 * @param embeddingProfileId 后端公开的嵌入配置标识。
 */
public record KnowledgeBasePatchRequest(
        @NotNull @Positive Long version,
        @Size(min = 1, max = 128) @Pattern(regexp = "[^\\x00]*") String name,
        @Size(max = 2000) @Pattern(regexp = "[^\\x00]*") String description,
        @Size(max = 128) String embeddingProfileId) {}
