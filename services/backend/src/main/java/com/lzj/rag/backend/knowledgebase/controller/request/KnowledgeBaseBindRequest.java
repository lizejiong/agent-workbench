package com.lzj.rag.backend.knowledgebase.controller.request;

import jakarta.validation.constraints.*;

/**
 * 为尚未绑定模型的知识库选择嵌入配置。
 *
 * @param version 当前记录版本或本次请求固定的版本标记。
 * @param embeddingProfileId 后端公开的嵌入配置标识。
 */
public record KnowledgeBaseBindRequest(
        @NotNull @Positive Long version, @NotBlank String embeddingProfileId) {}
