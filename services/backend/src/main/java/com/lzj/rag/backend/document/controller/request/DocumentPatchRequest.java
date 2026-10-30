package com.lzj.rag.backend.document.controller.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

/**
 * 修改文档名称、描述或处理配置的请求。
 *
 * @param version 当前记录版本或本次请求固定的版本标记。
 * @param name 当前业务资源或策略的公开名称。
 * @param ingestionSpec 文档固定的解析及切片配置。
 */
public record DocumentPatchRequest(
        @NotNull @Positive Long version,
        @Size(max = 255) String name,
        @Valid IngestionSpecRequest ingestionSpec) {}
