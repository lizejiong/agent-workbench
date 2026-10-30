package com.lzj.rag.backend.search.converter;

import com.lzj.rag.backend.search.controller.request.SearchRequest;
import com.lzj.rag.backend.search.controller.vo.ChannelScoreVO;
import com.lzj.rag.backend.search.controller.vo.ChannelStateVO;
import com.lzj.rag.backend.search.controller.vo.EvidenceVO;
import com.lzj.rag.backend.search.controller.vo.IndexVersionVO;
import com.lzj.rag.backend.search.controller.vo.KeywordSyncVO;
import com.lzj.rag.backend.search.controller.vo.RetrievalTraceVO;
import com.lzj.rag.backend.search.controller.vo.RetrievalVO;
import com.lzj.rag.backend.search.service.bo.ChannelScoreBO;
import com.lzj.rag.backend.search.service.bo.ChannelStateBO;
import com.lzj.rag.backend.search.service.bo.EvidenceBO;
import com.lzj.rag.backend.search.service.bo.IndexVersionBO;
import com.lzj.rag.backend.search.service.bo.KeywordSyncBO;
import com.lzj.rag.backend.search.service.bo.RetrievalBO;
import com.lzj.rag.backend.search.service.bo.RetrievalTraceBO;
import com.lzj.rag.backend.search.service.bo.SearchBO;

/** 只执行请求、业务对象与响应之间的字段转换，不查询数据或调用外部系统。 */
public final class SearchWebConverter {

    /** 禁止实例化无状态的转换工具。 */
    private SearchWebConverter() {}

    /**
     * 将 HTTP 请求转换为可由多个入口复用的业务输入。
     *
     * @param value 当前需要转换的数据；为空时保留为空。
     * @return 对应边界的数据快照。
     */
    public static SearchBO toSearchBO(SearchRequest value) {
        if (value == null) return null;
        // 集合逐项转换，保持输入顺序与空值语义。
        return new SearchBO(
                value.query(),
                value.kbIds(),
                value.documentIds(),
                value.topK(),
                value.minScore(),
                value.strategy(),
                value.rerank(),
                value.minRerankScore());
    }

    /**
     * 将业务结果转换为独立的接口响应，保留原字段语义。
     *
     * @param value 当前需要转换的数据；为空时保留为空。
     * @return 对应边界的数据快照。
     */
    public static EvidenceVO toEvidenceVO(EvidenceBO value) {
        if (value == null) return null;
        // 集合逐项转换，保持输入顺序与空值语义。
        return new EvidenceVO(
                value.chunkId(),
                value.kbId(),
                value.documentId(),
                value.documentName(),
                value.chunkIndex(),
                value.generation(),
                value.content(),
                value.score(),
                value.scoreType(),
                value.sourceMetadata(),
                value.fileUrl(),
                value.fusionScore(),
                value.rerankScore(),
                value.channelScores() == null
                        ? null
                        : value.channelScores().stream()
                                .map(SearchWebConverter::toChannelScoreVO)
                                .toList());
    }

    /**
     * 将业务结果转换为独立的接口响应，保留原字段语义。
     *
     * @param value 当前需要转换的数据；为空时保留为空。
     * @return 对应边界的数据快照。
     */
    public static ChannelScoreVO toChannelScoreVO(ChannelScoreBO value) {
        if (value == null) return null;
        return new ChannelScoreVO(value.channel(), value.rank(), value.score(), value.scoreType());
    }

    /**
     * 将业务结果转换为独立的接口响应，保留原字段语义。
     *
     * @param value 当前需要转换的数据；为空时保留为空。
     * @return 对应边界的数据快照。
     */
    public static ChannelStateVO toChannelStateVO(ChannelStateBO value) {
        if (value == null) return null;
        return new ChannelStateVO(
                value.channel(), value.status(), value.candidates(), value.errorCode());
    }

    /**
     * 将业务结果转换为独立的接口响应，保留原字段语义。
     *
     * @param value 当前需要转换的数据；为空时保留为空。
     * @return 对应边界的数据快照。
     */
    public static RetrievalVO toRetrievalVO(RetrievalBO value) {
        if (value == null) return null;
        // 集合逐项转换，保持输入顺序与空值语义。
        return new RetrievalVO(
                value.evidence() == null
                        ? null
                        : value.evidence().stream().map(SearchWebConverter::toEvidenceVO).toList(),
                value.requestedStrategy(),
                value.strategy(),
                value.rerankStatus(),
                value.channels() == null
                        ? null
                        : value.channels().stream()
                                .map(SearchWebConverter::toChannelStateVO)
                                .toList(),
                value.channelCandidates(),
                value.fusionCandidates(),
                value.topK(),
                value.refillLimitReached(),
                value.minRerankScore(),
                value.status(),
                value.outcome(),
                SearchWebConverter.toRetrievalTraceVO(value.trace()));
    }

    /**
     * 将业务结果转换为独立的接口响应，保留原字段语义。
     *
     * @param value 当前需要转换的数据；为空时保留为空。
     * @return 对应边界的数据快照。
     */
    public static IndexVersionVO toIndexVersionVO(IndexVersionBO value) {
        if (value == null) return null;
        return new IndexVersionVO(
                value.kbId(),
                value.embeddingModelId(),
                value.embeddingConfigRevision(),
                value.aiConfigRevision());
    }

    /**
     * 将业务结果转换为独立的接口响应，保留原字段语义。
     *
     * @param value 当前需要转换的数据；为空时保留为空。
     * @return 对应边界的数据快照。
     */
    public static RetrievalTraceVO toRetrievalTraceVO(RetrievalTraceBO value) {
        if (value == null) return null;
        // 集合逐项转换，保持输入顺序与空值语义。
        return new RetrievalTraceVO(
                value.requestId(),
                value.pipelineVersion(),
                value.elapsedMs(),
                value.indexes() == null
                        ? null
                        : value.indexes().stream()
                                .map(SearchWebConverter::toIndexVersionVO)
                                .toList(),
                value.keywordIndexVersion(),
                value.rerankModel());
    }

    /**
     * 将业务结果转换为独立的接口响应，保留原字段语义。
     *
     * @param value 当前需要转换的数据；为空时保留为空。
     * @return 对应边界的数据快照。
     */
    public static KeywordSyncVO toKeywordSyncVO(KeywordSyncBO value) {
        if (value == null) return null;
        return new KeywordSyncVO(
                value.documentId(),
                value.revision(),
                value.syncedRevision(),
                value.status(),
                value.attempts(),
                value.nextRunAt(),
                value.leaseUntil(),
                value.errorCode(),
                value.syncedAt(),
                value.cleanupStatus(),
                value.cleanupErrorCode());
    }
}
