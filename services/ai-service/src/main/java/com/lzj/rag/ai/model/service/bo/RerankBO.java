package com.lzj.rag.ai.model.service.bo;

import java.util.List;

/**
 * 重排业务输入，保留文档身份与本次查询。
 *
 * @param query 重排所依据的查询文本。
 * @param documents 保持调用方顺序的待重排文档集合。
 * @param topN 最多返回的文档数，业务层将其限制在输入文档数以内。
 * @param model 本次实际使用或请求固定的逻辑模型别名。
 */
public record RerankBO(String query, List<DocumentBO> documents, Integer topN, String model) {

    /**
     * 业务重排文档，保留调用方提供的身份与原文。
     *
     * @param id 调用方提供的文档唯一标识，重排后原样返回。
     * @param text 待处理或返回的文本内容。
     */
    public record DocumentBO(String id, String text) {}
}
