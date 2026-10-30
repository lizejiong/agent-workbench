package com.lzj.rag.backend.chunk.converter;

import com.lzj.rag.backend.chunk.controller.request.ChunkContentRequest;
import com.lzj.rag.backend.chunk.controller.vo.ChunkChangedVO;
import com.lzj.rag.backend.chunk.controller.vo.ChunkViewVO;
import com.lzj.rag.backend.chunk.service.bo.ChunkChangedBO;
import com.lzj.rag.backend.chunk.service.bo.ChunkContentBO;
import com.lzj.rag.backend.chunk.service.bo.ChunkViewBO;

/** 只执行请求、业务对象与响应之间的字段转换，不查询数据或调用外部系统。 */
public final class ChunkWebConverter {

    /** 禁止实例化无状态的转换工具。 */
    private ChunkWebConverter() {}

    /**
     * 将 HTTP 请求转换为可由多个入口复用的业务输入。
     *
     * @param value 当前需要转换的数据；为空时保留为空。
     * @return 对应边界的数据快照。
     */
    public static ChunkContentBO toChunkContentBO(ChunkContentRequest value) {
        if (value == null) return null;
        return new ChunkContentBO(value.version(), value.content());
    }

    /**
     * 将业务结果转换为独立的接口响应，保留原字段语义。
     *
     * @param value 当前需要转换的数据；为空时保留为空。
     * @return 对应边界的数据快照。
     */
    public static ChunkViewVO toChunkViewVO(ChunkViewBO value) {
        if (value == null) return null;
        return new ChunkViewVO(
                value.id(),
                value.documentId(),
                value.kbId(),
                value.generation(),
                value.chunkIndex(),
                value.content(),
                value.embeddingText(),
                value.contentHash(),
                value.charCount(),
                value.tokenCount(),
                value.tokenCountEstimated(),
                value.enabled(),
                value.edited(),
                value.metadata(),
                value.createdBy(),
                value.updatedBy(),
                value.createdAt(),
                value.updatedAt());
    }

    /**
     * 将业务结果转换为独立的接口响应，保留原字段语义。
     *
     * @param value 当前需要转换的数据；为空时保留为空。
     * @return 对应边界的数据快照。
     */
    public static ChunkChangedVO toChunkChangedVO(ChunkChangedBO value) {
        if (value == null) return null;
        // 集合逐项转换，保持输入顺序与空值语义。
        return new ChunkChangedVO(
                value.documentVersion(),
                value.manualRevision(),
                value.chunkCount(),
                value.chunks() == null
                        ? null
                        : value.chunks().stream().map(ChunkWebConverter::toChunkViewVO).toList());
    }
}
