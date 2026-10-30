package com.lzj.rag.backend.document.controller.vo;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 文档响应快照，公开处理状态和当前有效代次，不泄露对象存储凭据。
 *
 * @param id 当前业务对象的唯一标识。
 * @param kbId 知识库唯一标识。
 * @param name 当前业务资源或策略的公开名称。
 * @param originalFilename 源文件原始名称。
 * @param sourceType 文档来源类型，如本地上传或 URL 导入。
 * @param objectKey 对象存储中的源文件键。
 * @param mimeType 源文件 MIME 类型。
 * @param fileSize 源文件字节数。
 * @param contentHash 源内容或切片正文的 SHA-256 摘要。
 * @param enabled 是否启用该资源或能力。
 * @param processStatus 文档当前入库处理状态。
 * @param ingestionSpec 文档固定的解析及切片配置。
 * @param configRevision 业务处理配置修订，用于识别任务输入是否已失效。
 * @param activeGeneration 当前可被检索的已发布代次；尚未发布时为空。
 * @param chunkCount 切片数量。
 * @param version 当前记录版本或本次请求固定的版本标记。
 * @param manualRevision 文档人工维护修订计数。
 * @param hasManualChanges 文档是否存在需要覆盖确认的人工维护变更。
 * @param createdAt 记录创建时间。
 * @param updatedAt 记录最近更新时间。
 * @param sourceUrl 远程文档配置的原始源地址。
 * @param lastIngestedAt 文档最近一次成功发布入库结果的时间。
 */
public record DocumentVO(
        UUID id,
        UUID kbId,
        String name,
        String originalFilename,
        String sourceType,
        String objectKey,
        String mimeType,
        long fileSize,
        String contentHash,
        boolean enabled,
        String processStatus,
        IngestionSpecVO ingestionSpec,
        long configRevision,
        Long activeGeneration,
        long chunkCount,
        long version,
        long manualRevision,
        boolean hasManualChanges,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        String sourceUrl,
        OffsetDateTime lastIngestedAt) {}
