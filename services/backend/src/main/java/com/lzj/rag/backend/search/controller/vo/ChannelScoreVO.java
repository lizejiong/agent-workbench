package com.lzj.rag.backend.search.controller.vo;

import java.util.*;

/**
 * 单个召回通道的原始排名与分数，避免混淆不同通道的评分尺度。
 *
 * @param channel 当前召回通道名称或通道实例。
 * @param rank 候选在当前通道中的排名，从一开始计数。
 * @param score 当前候选的相关性或融合分数。
 * @param scoreType 评分语义标识，用于区分余弦相似度、BM25 与 RRF。
 */
public record ChannelScoreVO(String channel, int rank, double score, String scoreType, String subQuery) {}
