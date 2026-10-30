package com.lzj.rag.backend.ingestion.controller.request;

import jakarta.validation.constraints.*;

/**
 * 入库提交的幂等键和人工内容覆盖确认。
 *
 * @param requestId 请求跟踪标识或业务幂等键，按当前接口约定使用。
 * @param confirmOverwriteEdited 是否已显式确认覆盖人工维护的内容与启停设置。
 * @param expectedDocumentVersion 调用方已审阅并要求匹配的文档版本。
 */
public record IngestionSubmitRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:-]{7,127}") String requestId,
        boolean confirmOverwriteEdited,
        @Positive Long expectedDocumentVersion) {}
