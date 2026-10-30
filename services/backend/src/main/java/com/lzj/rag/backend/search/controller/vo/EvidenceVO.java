package com.lzj.rag.backend.search.controller.vo;

import java.util.*;

/**
 * 可展示的当前切片证据，保留来源坐标、通道分数和下载入口。
 *
 * @param chunkId 切片唯一标识。
 * @param kbId 知识库唯一标识。
 * @param documentId 文档唯一标识。
 * @param documentName 文档对外展示名称。
 * @param chunkIndex 切片在所属文档代次内的顺序位置。
 * @param generation 切片或任务所属的数据代次。
 * @param content 用于展示或维护的正文内容。
 * @param score 当前候选的相关性或融合分数。
 * @param scoreType 评分语义标识，用于区分余弦相似度、BM25 与 RRF。
 * @param sourceMetadata 允许对外展示的切片来源定位信息。
 * @param fileUrl 需要业务授权的源文件读取入口。
 * @param fusionScore RRF 融合分数。
 * @param rerankScore 模型重排分数；未应用重排时为空。
 * @param channelScores 各召回通道对当前切片的原始排名与评分。
 */
public record EvidenceVO(
        UUID chunkId,
        UUID kbId,
        UUID documentId,
        String documentName,
        int chunkIndex,
        long generation,
        String content,
        double score,
        String scoreType,
        Map<String, Object> sourceMetadata,
        String fileUrl,
        Double fusionScore,
        Double rerankScore,
        List<ChannelScoreVO> channelScores) {}
