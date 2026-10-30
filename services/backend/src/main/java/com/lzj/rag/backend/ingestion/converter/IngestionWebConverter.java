package com.lzj.rag.backend.ingestion.converter;

import com.lzj.rag.backend.ingestion.controller.vo.IngestionJobVO;
import com.lzj.rag.backend.ingestion.controller.vo.IngestionStageVO;
import com.lzj.rag.backend.ingestion.service.bo.IngestionJobBO;
import com.lzj.rag.backend.ingestion.service.bo.IngestionStageBO;

/** 只执行请求、业务对象与响应之间的字段转换，不查询数据或调用外部系统。 */
public final class IngestionWebConverter {

    /** 禁止实例化无状态的转换工具。 */
    private IngestionWebConverter() {}

    /**
     * 将业务结果转换为独立的接口响应，保留原字段语义。
     *
     * @param value 当前需要转换的数据；为空时保留为空。
     * @return 对应边界的数据快照。
     */
    public static IngestionJobVO toIngestionJobVO(IngestionJobBO value) {
        if (value == null) return null;
        return new IngestionJobVO(
                value.id(),
                value.requestId(),
                value.documentId(),
                value.documentConfigRevision(),
                value.targetGeneration(),
                value.jobType(),
                value.status(),
                value.stage(),
                value.attempts(),
                value.maxAttempts(),
                value.createdAt(),
                value.startedAt(),
                value.completedAt(),
                value.nextRunAt(),
                value.leaseUntil(),
                value.errorCode(),
                value.errorMessage(),
                value.aiConfigRevision(),
                value.retryOf());
    }

    /**
     * 将业务结果转换为独立的接口响应，保留原字段语义。
     *
     * @param value 当前需要转换的数据；为空时保留为空。
     * @return 对应边界的数据快照。
     */
    public static IngestionStageVO toIngestionStageVO(IngestionStageBO value) {
        if (value == null) return null;
        return new IngestionStageVO(
                value.stage(),
                value.attempt(),
                value.status(),
                value.startedAt(),
                value.endedAt(),
                value.durationMs(),
                value.inputCount(),
                value.inputUnit(),
                value.outputCount(),
                value.outputUnit(),
                value.errorCode(),
                value.errorMessage());
    }
}
