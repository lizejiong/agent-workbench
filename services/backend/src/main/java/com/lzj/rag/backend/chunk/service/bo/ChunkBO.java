package com.lzj.rag.backend.chunk.service.bo;

import java.util.Map;
import java.util.UUID;

/**
 * 解析内容生成的待入库切片，保存展示正文、嵌入文本和可追溯来源。
 *
 * @param id 当前业务对象的唯一标识。
 * @param chunkIndex 切片在所属文档代次内的顺序位置。
 * @param content 用于展示或维护的正文内容。
 * @param embeddingText 发送给嵌入模型的文本，可能包含章节或表格语义补充。
 * @param contentHash 源内容或切片正文的 SHA-256 摘要。
 * @param charCount 按 Unicode 码点统计的正文字符数。
 * @param metadata 上传业务元数据或切片来源定位字段。
 */
public record ChunkBO(
        UUID id,
        int chunkIndex,
        String content,
        String embeddingText,
        String contentHash,
        int charCount,
        Map<String, Object> metadata) {}
