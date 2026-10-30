package com.lzj.rag.backend.chunk.controller.vo;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * 返回给维护界面的切片内容、来源及人工编辑状态。
 *
 * @param id 当前业务对象的唯一标识。
 * @param documentId 文档唯一标识。
 * @param kbId 知识库唯一标识。
 * @param generation 切片或任务所属的数据代次。
 * @param chunkIndex 切片在所属文档代次内的顺序位置。
 * @param content 用于展示或维护的正文内容。
 * @param embeddingText 发送给嵌入模型的文本，可能包含章节或表格语义补充。
 * @param contentHash 源内容或切片正文的 SHA-256 摘要。
 * @param charCount 按 Unicode 码点统计的正文字符数。
 * @param tokenCount 切片的 Token 计数或估算值。
 * @param tokenCountEstimated Token 数是否来自估算。
 * @param enabled 是否启用该资源或能力。
 * @param edited 切片是否经过人工编辑。
 * @param metadata 上传业务元数据或切片来源定位字段。
 * @param createdBy 创建该记录的业务用户标识。
 * @param updatedBy 最近修改记录的业务用户标识。
 * @param createdAt 记录创建时间。
 * @param updatedAt 记录最近更新时间。
 */
public record ChunkViewVO(
        UUID id,
        UUID documentId,
        UUID kbId,
        long generation,
        int chunkIndex,
        String content,
        String embeddingText,
        String contentHash,
        int charCount,
        Integer tokenCount,
        boolean tokenCountEstimated,
        boolean enabled,
        boolean edited,
        Map<String, Object> metadata,
        String createdBy,
        String updatedBy,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {}
