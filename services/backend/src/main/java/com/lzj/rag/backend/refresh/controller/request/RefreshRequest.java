package com.lzj.rag.backend.refresh.controller.request;

import jakarta.validation.constraints.*;

/**
 * 手动刷新请求，允许显式确认覆盖人工维护内容。
 *
 * @param requestId 请求跟踪标识或业务幂等键，按当前接口约定使用。
 * @param confirmOverwriteEdited 是否已显式确认覆盖人工维护的内容与启停设置。
 * @param expectedDocumentVersion 调用方已审阅并要求匹配的文档版本。
 */
public record RefreshRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:-]{7,127}") String requestId,
        boolean confirmOverwriteEdited,
        @Positive Long expectedDocumentVersion) {}
