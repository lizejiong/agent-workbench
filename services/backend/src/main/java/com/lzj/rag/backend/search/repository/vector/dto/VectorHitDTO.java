package com.lzj.rag.backend.search.repository.vector.dto;

import java.util.*;

/**
 * 向量召回命中，保留切片身份、代次及余弦距离。
 *
 * @param chunkId 切片唯一标识。
 * @param kbId 知识库唯一标识。
 * @param documentId 文档唯一标识。
 * @param documentName 文档对外展示名称。
 * @param generation 切片或任务所属的数据代次。
 * @param chunkIndex 切片在所属文档代次内的顺序位置。
 * @param content 用于展示或维护的正文内容。
 * @param embeddingText 发送给嵌入模型的文本，可能包含章节或表格语义补充。
 * @param metadataJson 持久化存储的切片来源元数据 JSON。
 * @param cosineDistance 向量间的余弦距离，越小表示越相似。
 */
public record VectorHitDTO(
        UUID chunkId,
        UUID kbId,
        UUID documentId,
        String documentName,
        long generation,
        int chunkIndex,
        String content,
        String embeddingText,
        String metadataJson,
        double cosineDistance) {}
