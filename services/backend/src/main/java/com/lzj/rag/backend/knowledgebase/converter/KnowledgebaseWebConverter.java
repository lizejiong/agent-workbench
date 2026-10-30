package com.lzj.rag.backend.knowledgebase.converter;

import com.lzj.rag.backend.knowledgebase.controller.request.KnowledgeBaseBindRequest;
import com.lzj.rag.backend.knowledgebase.controller.request.KnowledgeBaseCreateRequest;
import com.lzj.rag.backend.knowledgebase.controller.request.KnowledgeBasePatchRequest;
import com.lzj.rag.backend.knowledgebase.controller.request.KnowledgeBaseUpdateRequest;
import com.lzj.rag.backend.knowledgebase.controller.vo.KnowledgeBaseVO;
import com.lzj.rag.backend.knowledgebase.service.bo.KnowledgeBaseBO;
import com.lzj.rag.backend.knowledgebase.service.bo.KnowledgeBaseBindBO;
import com.lzj.rag.backend.knowledgebase.service.bo.KnowledgeBaseCreateBO;
import com.lzj.rag.backend.knowledgebase.service.bo.KnowledgeBasePatchBO;
import com.lzj.rag.backend.knowledgebase.service.bo.KnowledgeBaseUpdateBO;

/** 只执行请求、业务对象与响应之间的字段转换，不查询数据或调用外部系统。 */
public final class KnowledgebaseWebConverter {

    /** 禁止实例化无状态的转换工具。 */
    private KnowledgebaseWebConverter() {}

    /**
     * 将 HTTP 请求转换为可由多个入口复用的业务输入。
     *
     * @param value 当前需要转换的数据；为空时保留为空。
     * @return 对应边界的数据快照。
     */
    public static KnowledgeBaseCreateBO toKnowledgeBaseCreateBO(KnowledgeBaseCreateRequest value) {
        if (value == null) return null;
        return new KnowledgeBaseCreateBO(
                value.name(), value.description(), value.embeddingProfileId());
    }

    /**
     * 将 HTTP 请求转换为可由多个入口复用的业务输入。
     *
     * @param value 当前需要转换的数据；为空时保留为空。
     * @return 对应边界的数据快照。
     */
    public static KnowledgeBaseUpdateBO toKnowledgeBaseUpdateBO(KnowledgeBaseUpdateRequest value) {
        if (value == null) return null;
        return new KnowledgeBaseUpdateBO(value.name(), value.description(), value.version());
    }

    /**
     * 将 HTTP 请求转换为可由多个入口复用的业务输入。
     *
     * @param value 当前需要转换的数据；为空时保留为空。
     * @return 对应边界的数据快照。
     */
    public static KnowledgeBasePatchBO toKnowledgeBasePatchBO(KnowledgeBasePatchRequest value) {
        if (value == null) return null;
        return new KnowledgeBasePatchBO(
                value.version(), value.name(), value.description(), value.embeddingProfileId());
    }

    /**
     * 将 HTTP 请求转换为可由多个入口复用的业务输入。
     *
     * @param value 当前需要转换的数据；为空时保留为空。
     * @return 对应边界的数据快照。
     */
    public static KnowledgeBaseBindBO toKnowledgeBaseBindBO(KnowledgeBaseBindRequest value) {
        if (value == null) return null;
        return new KnowledgeBaseBindBO(value.version(), value.embeddingProfileId());
    }

    /**
     * 将业务结果转换为独立的接口响应，保留原字段语义。
     *
     * @param value 当前需要转换的数据；为空时保留为空。
     * @return 对应边界的数据快照。
     */
    public static KnowledgeBaseVO toKnowledgeBaseVO(KnowledgeBaseBO value) {
        if (value == null) return null;
        return new KnowledgeBaseVO(
                value.id(),
                value.name(),
                value.description(),
                value.version(),
                value.createdAt(),
                value.updatedAt(),
                value.bindingStatus(),
                value.embeddingProfileId(),
                value.embeddingModelId(),
                value.embeddingProvider(),
                value.embeddingDimension(),
                value.embeddingConfigRevision(),
                value.vectorNamespace(),
                value.embeddingAiConfigRevision(),
                value.documentCount());
    }
}
