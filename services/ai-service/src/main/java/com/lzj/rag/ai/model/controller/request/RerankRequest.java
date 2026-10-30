package com.lzj.rag.ai.model.controller.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import java.util.List;

/**
 * 重排接口请求，文档 ID 用于把模型排序结果映射回原业务对象。
 *
 * @param query 重排所依据的查询文本。
 * @param documents 保持调用方顺序的待重排文档集合。
 * @param topN 最多返回的文档数，业务层将其限制在输入文档数以内。
 * @param model 本次实际使用或请求固定的逻辑模型别名。
 */
public record RerankRequest(
        @NotBlank String query,
        @Valid List<DocumentRequest> documents,
        @Positive Integer topN,
        String model) {

    /**
     * HTTP 请求重排文档，保留调用方提供的身份与原文。
     *
     * @param id 调用方提供的文档唯一标识，重排后原样返回。
     * @param text 待处理或返回的文本内容。
     */
    public record DocumentRequest(@NotBlank String id, @NotBlank String text) {}
}
