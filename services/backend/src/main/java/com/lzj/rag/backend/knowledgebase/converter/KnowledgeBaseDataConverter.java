package com.lzj.rag.backend.knowledgebase.converter;

import com.lzj.rag.backend.knowledgebase.dao.entity.KnowledgeBaseDO;
import com.lzj.rag.backend.knowledgebase.service.bo.KnowledgeBaseBO;

/** 知识库持久化对象到业务结果的纯字段转换，不读取数据库。 */
public final class KnowledgeBaseDataConverter {

    /**
     * 根据知识库实体和可选文档计数创建公开响应。
     *
     * @param base 本次操作涉及的知识库实体。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public static KnowledgeBaseBO from(KnowledgeBaseDO base) {
        return from(base, 0);
    }

    /**
     * 根据知识库实体和可选文档计数创建公开响应。
     *
     * @param base 本次操作涉及的知识库实体。
     * @param documentCount 知识库内符合统计条件的文档数量。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public static KnowledgeBaseBO from(KnowledgeBaseDO base, long documentCount) {
        return new KnowledgeBaseBO(
                base.getId(),
                base.getName(),
                base.getDescription(),
                base.getVersion(),
                base.getCreatedAt(),
                base.getUpdatedAt(),
                base.getEmbeddingModelId() == null ? "UNBOUND" : "BOUND",
                base.getEmbeddingProfileId(),
                base.getEmbeddingModelId(),
                base.getEmbeddingProvider(),
                base.getEmbeddingDimension(),
                base.getEmbeddingConfigRevision(),
                base.getVectorNamespace(),
                base.getEmbeddingAiConfigRevision(),
                documentCount);
    }
}
