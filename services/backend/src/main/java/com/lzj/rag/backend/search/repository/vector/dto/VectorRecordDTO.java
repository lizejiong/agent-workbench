package com.lzj.rag.backend.search.repository.vector.dto;

import java.util.*;

/**
 * 待写入的切片向量、正文和元数据。
 *
 * @param chunkId 切片唯一标识。
 * @param documentId 文档唯一标识。
 * @param generation 切片或任务所属的数据代次。
 * @param embedding 切片的浮点嵌入向量。
 * @param aiConfigRevision 本次实际使用或任务固定的 AI 配置修订摘要。
 * @param metadata 上传业务元数据或切片来源定位字段。
 */
public record VectorRecordDTO(
        UUID chunkId,
        UUID documentId,
        long generation,
        float[] embedding,
        String aiConfigRevision,
        Map<String, Object> metadata) {}
