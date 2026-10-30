package com.lzj.rag.backend.chunk.controller.vo;

import java.util.*;

/**
 * 维护完成后的文档版本和受影响切片。
 *
 * @param documentVersion 本次操作固定或返回的文档版本。
 * @param manualRevision 文档人工维护修订计数。
 * @param chunkCount 切片数量。
 * @param chunks 本次处理的有序切片集合。
 */
public record ChunkChangedVO(
        long documentVersion, long manualRevision, long chunkCount, List<ChunkViewVO> chunks) {}
