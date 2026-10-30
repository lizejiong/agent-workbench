package com.lzj.rag.backend.refresh.converter;

import com.lzj.rag.backend.document.converter.DocumentWebConverter;
import com.lzj.rag.backend.refresh.controller.request.RefreshRequest;
import com.lzj.rag.backend.refresh.controller.request.RefreshScheduleRequest;
import com.lzj.rag.backend.refresh.controller.request.RemoteImportRequest;
import com.lzj.rag.backend.refresh.controller.vo.RefreshRunVO;
import com.lzj.rag.backend.refresh.controller.vo.RefreshScheduleResultVO;
import com.lzj.rag.backend.refresh.controller.vo.RemoteImportedVO;
import com.lzj.rag.backend.refresh.service.bo.RefreshBO;
import com.lzj.rag.backend.refresh.service.bo.RefreshRunBO;
import com.lzj.rag.backend.refresh.service.bo.RefreshScheduleBO;
import com.lzj.rag.backend.refresh.service.bo.RefreshScheduleResultBO;
import com.lzj.rag.backend.refresh.service.bo.RemoteImportBO;
import com.lzj.rag.backend.refresh.service.bo.RemoteImportedBO;

/** 只执行请求、业务对象与响应之间的字段转换，不查询数据或调用外部系统。 */
public final class RefreshWebConverter {

    /** 禁止实例化无状态的转换工具。 */
    private RefreshWebConverter() {}

    /**
     * 将 HTTP 请求转换为可由多个入口复用的业务输入。
     *
     * @param value 当前需要转换的数据；为空时保留为空。
     * @return 对应边界的数据快照。
     */
    public static RemoteImportBO toRemoteImportBO(RemoteImportRequest value) {
        if (value == null) return null;
        return new RemoteImportBO(
                value.url(),
                value.filename(),
                value.name(),
                DocumentWebConverter.toIngestionSpecBO(value.ingestionSpec()),
                value.ingest());
    }

    /**
     * 将 HTTP 请求转换为可由多个入口复用的业务输入。
     *
     * @param value 当前需要转换的数据；为空时保留为空。
     * @return 对应边界的数据快照。
     */
    public static RefreshScheduleBO toRefreshScheduleBO(RefreshScheduleRequest value) {
        if (value == null) return null;
        return new RefreshScheduleBO(
                value.version(), value.enabled(), value.cron(), value.timezone());
    }

    /**
     * 将 HTTP 请求转换为可由多个入口复用的业务输入。
     *
     * @param value 当前需要转换的数据；为空时保留为空。
     * @return 对应边界的数据快照。
     */
    public static RefreshBO toRefreshBO(RefreshRequest value) {
        if (value == null) return null;
        return new RefreshBO(
                value.requestId(), value.confirmOverwriteEdited(), value.expectedDocumentVersion());
    }

    /**
     * 将业务结果转换为独立的接口响应，保留原字段语义。
     *
     * @param value 当前需要转换的数据；为空时保留为空。
     * @return 对应边界的数据快照。
     */
    public static RemoteImportedVO toRemoteImportedVO(RemoteImportedBO value) {
        if (value == null) return null;
        return new RemoteImportedVO(
                DocumentWebConverter.toDocumentVO(value.document()),
                value.jobId(),
                value.replayed());
    }

    /**
     * 将业务结果转换为独立的接口响应，保留原字段语义。
     *
     * @param value 当前需要转换的数据；为空时保留为空。
     * @return 对应边界的数据快照。
     */
    public static RefreshScheduleResultVO toRefreshScheduleResultVO(RefreshScheduleResultBO value) {
        if (value == null) return null;
        return new RefreshScheduleResultVO(
                value.documentId(),
                value.enabled(),
                value.cron(),
                value.timezone(),
                value.version(),
                value.nextRunAt(),
                value.lastStatus(),
                value.lastReason(),
                value.lastRunId(),
                value.lastContentHash(),
                value.consecutiveFailures());
    }

    /**
     * 将业务结果转换为独立的接口响应，保留原字段语义。
     *
     * @param value 当前需要转换的数据；为空时保留为空。
     * @return 对应边界的数据快照。
     */
    public static RefreshRunVO toRefreshRunVO(RefreshRunBO value) {
        if (value == null) return null;
        return new RefreshRunVO(
                value.id(),
                value.documentId(),
                value.requestId(),
                value.triggerType(),
                value.status(),
                value.jobId(),
                value.attempts(),
                value.maxAttempts(),
                value.httpStatus(),
                value.reason(),
                value.errorMessage(),
                value.nextRunAt(),
                value.startedAt(),
                value.completedAt(),
                value.durationMs());
    }
}
