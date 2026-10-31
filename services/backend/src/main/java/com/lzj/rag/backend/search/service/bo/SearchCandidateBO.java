package com.lzj.rag.backend.search.service.bo;

import java.util.*;

/**
 * 单通道候选，包含证据、排名、原始分数和可选关键词同步修订。
 *
 * @param evidence 从当前有效代次加载的可公开切片证据。
 * @param channel 当前召回通道名称或通道实例。
 * @param rank 候选在当前通道中的排名，从一开始计数。
 * @param rawScore 召回通道未经跨通道归一化的原始分数。
 * @param scoreType 评分语义标识，用于区分余弦相似度、BM25 与 RRF。
 * @param syncRevision 本次关键词同步任务固定的数据修订。
 * @param contentHash 源内容或切片正文的 SHA-256 摘要。
 */
public record SearchCandidateBO(
        EvidenceBO evidence,
        String channel,
        int rank,
        double rawScore,
        String scoreType,
        long syncRevision,
        String contentHash, String subQuery) {
    public SearchCandidateBO(EvidenceBO evidence,String channel,int rank,double rawScore,String scoreType,long syncRevision,String contentHash) {
        this(evidence,channel,rank,rawScore,scoreType,syncRevision,contentHash,null);
    }
}
