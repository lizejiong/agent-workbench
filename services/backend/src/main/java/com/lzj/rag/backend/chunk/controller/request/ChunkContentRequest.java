package com.lzj.rag.backend.chunk.controller.request;

import jakarta.validation.constraints.*;

import java.util.*;

/**
 * 新增或编辑切片的正文与预期文档版本。
 *
 * @param version 当前记录版本或本次请求固定的版本标记。
 * @param content 用于展示或维护的正文内容。
 */
public record ChunkContentRequest(
        @NotNull @Positive Long version, @NotBlank @Size(max = 131072) String content) {}
