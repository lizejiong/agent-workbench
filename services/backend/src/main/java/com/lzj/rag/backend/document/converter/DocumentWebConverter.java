package com.lzj.rag.backend.document.converter;

import com.lzj.rag.backend.document.controller.request.DocumentPatchRequest;
import com.lzj.rag.backend.document.controller.request.IngestionSpecRequest;
import com.lzj.rag.backend.document.controller.request.SplitterRequest;
import com.lzj.rag.backend.document.controller.vo.DocumentCleanupVO;
import com.lzj.rag.backend.document.controller.vo.DocumentVO;
import com.lzj.rag.backend.document.controller.vo.IngestionSpecVO;
import com.lzj.rag.backend.document.controller.vo.SplitterVO;
import com.lzj.rag.backend.document.controller.vo.UploadOperationResultVO;
import com.lzj.rag.backend.document.service.bo.DocumentBO;
import com.lzj.rag.backend.document.service.bo.DocumentCleanupBO;
import com.lzj.rag.backend.document.service.bo.DocumentPatchBO;
import com.lzj.rag.backend.document.service.bo.IngestionSpecBO;
import com.lzj.rag.backend.document.service.bo.SplitterBO;
import com.lzj.rag.backend.document.service.bo.UploadOperationResultBO;
import com.lzj.rag.backend.document.service.bo.UploadSourceBO;

/** 只执行请求、业务对象与响应之间的字段转换，不查询数据或调用外部系统。 */
public final class DocumentWebConverter {

    /** 禁止实例化无状态的转换工具。 */
    private DocumentWebConverter() {}

    /**
     * 将 HTTP 请求转换为可由多个入口复用的业务输入。
     *
     * @param value 当前需要转换的数据；为空时保留为空。
     * @return 对应边界的数据快照。
     */
    public static DocumentPatchBO toDocumentPatchBO(DocumentPatchRequest value) {
        if (value == null) return null;
        return new DocumentPatchBO(
                value.version(),
                value.name(),
                DocumentWebConverter.toIngestionSpecBO(value.ingestionSpec()));
    }

    /**
     * 将 HTTP 请求转换为可由多个入口复用的业务输入。
     *
     * @param value 当前需要转换的数据；为空时保留为空。
     * @return 对应边界的数据快照。
     */
    public static IngestionSpecBO toIngestionSpecBO(IngestionSpecRequest value) {
        if (value == null) return null;
        return new IngestionSpecBO(
                value.schemaVersion(),
                value.parser(),
                DocumentWebConverter.toSplitterBO(value.splitter()),
                value.parseProfile());
    }

    /**
     * 将 HTTP 请求转换为可由多个入口复用的业务输入。
     *
     * @param value 当前需要转换的数据；为空时保留为空。
     * @return 对应边界的数据快照。
     */
    public static SplitterBO toSplitterBO(SplitterRequest value) {
        if (value == null) return null;
        return new SplitterBO(
                value.kind(),
                value.maxChars(),
                value.overlapChars(),
                value.tableRows(),
                value.toleranceChars());
    }

    /**
     * 将业务结果转换为独立的接口响应，保留原字段语义。
     *
     * @param value 当前需要转换的数据；为空时保留为空。
     * @return 对应边界的数据快照。
     */
    public static DocumentVO toDocumentVO(DocumentBO value) {
        if (value == null) return null;
        return new DocumentVO(
                value.id(),
                value.kbId(),
                value.name(),
                value.originalFilename(),
                value.sourceType(),
                value.objectKey(),
                value.mimeType(),
                value.fileSize(),
                value.contentHash(),
                value.enabled(),
                value.processStatus(),
                DocumentWebConverter.toIngestionSpecVO(value.ingestionSpec()),
                value.configRevision(),
                value.activeGeneration(),
                value.chunkCount(),
                value.version(),
                value.manualRevision(),
                value.hasManualChanges(),
                value.createdAt(),
                value.updatedAt(),
                value.sourceUrl(),
                value.lastIngestedAt());
    }

    /**
     * 将业务结果转换为独立的接口响应，保留原字段语义。
     *
     * @param value 当前需要转换的数据；为空时保留为空。
     * @return 对应边界的数据快照。
     */
    public static UploadOperationResultVO toUploadOperationResultVO(UploadOperationResultBO value) {
        if (value == null) return null;
        return new UploadOperationResultVO(
                value.id(),
                value.idempotencyKey(),
                value.status(),
                value.attempts(),
                value.nextRunAt(),
                value.leaseUntil(),
                value.errorCode(),
                value.updatedAt());
    }

    /**
     * 将业务结果转换为独立的接口响应，保留原字段语义。
     *
     * @param value 当前需要转换的数据；为空时保留为空。
     * @return 对应边界的数据快照。
     */
    public static DocumentCleanupVO toDocumentCleanupVO(DocumentCleanupBO value) {
        if (value == null) return null;
        return new DocumentCleanupVO(
                value.id(),
                value.documentId(),
                value.status(),
                value.stage(),
                value.attempts(),
                value.maxAttempts(),
                value.nextRunAt(),
                value.leaseUntil(),
                value.errorCode(),
                value.errorMessage(),
                value.createdAt(),
                value.completedAt());
    }

    /**
     * 将业务结果转换为独立的接口响应，保留原字段语义。
     *
     * @param value 当前需要转换的数据；为空时保留为空。
     * @return 对应边界的数据快照。
     */
    public static IngestionSpecVO toIngestionSpecVO(IngestionSpecBO value) {
        if (value == null) return null;
        return new IngestionSpecVO(
                value.schemaVersion(),
                value.parser(),
                DocumentWebConverter.toSplitterVO(value.splitter()),
                value.parseProfile());
    }

    /**
     * 将业务结果转换为独立的接口响应，保留原字段语义。
     *
     * @param value 当前需要转换的数据；为空时保留为空。
     * @return 对应边界的数据快照。
     */
    public static SplitterVO toSplitterVO(SplitterBO value) {
        if (value == null) return null;
        return new SplitterVO(
                value.kind(),
                value.maxChars(),
                value.overlapChars(),
                value.tableRows(),
                value.toleranceChars());
    }

    /**
     * 将 HTTP 上传文件适配为不依赖 Web 类型的业务源输入。
     *
     * @param file 当前 HTTP 上传文件。
     * @return 保留文件名、声明大小和流读取方式的业务输入。
     */
    public static UploadSourceBO toBO(org.springframework.web.multipart.MultipartFile file) {
        return new UploadSourceBO(file.getOriginalFilename(), file.getSize(), file::getInputStream);
    }
}
