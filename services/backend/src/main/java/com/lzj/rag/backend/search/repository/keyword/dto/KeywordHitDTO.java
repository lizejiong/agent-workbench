package com.lzj.rag.backend.search.repository.keyword.dto;

import java.util.*;

/**
 * 关键词索引返回的切片身份、分数及同步修订。
 *
 * @param chunkId 切片唯一标识。
 * @param syncRevision 本次关键词同步任务固定的数据修订。
 * @param contentHash 源内容或切片正文的 SHA-256 摘要。
 * @param rank 候选在当前通道中的排名，从一开始计数。
 * @param score 当前候选的相关性或融合分数。
 */
public record KeywordHitDTO(
        UUID chunkId, long syncRevision, String contentHash, int rank, double score) {}
