package com.lzj.rag.backend.search.controller.vo;

import java.util.*;

/**
 * 融合后的证据、降级状态和结果确定性说明。
 *
 * @param evidence 从当前有效代次加载的可公开切片证据。
 * @param requestedStrategy 调用方请求的检索策略。
 * @param strategy 本次实际执行的检索策略。
 * @param rerankStatus 重排是否应用、跳过或因失败降级的状态。
 * @param channels 参与召回的通道集合或按名称索引的通道状态。
 * @param channelCandidates 每个召回通道的候选数量。
 * @param fusionCandidates 融合后保留的候选数量。
 * @param topK 本次请求希望保留的最终证据条数。
 * @param refillLimitReached 召回过滤无效候选后是否达到补充查询次数上限。
 * @param minRerankScore 模型重排分数的最低阈值，不能用融合分数替代。
 * @param status 当前业务操作或任务状态。
 * @param outcome 检索结果的证据确定性分类。
 * @param trace 本次检索的策略、耗时和索引身份追踪信息。
 */
public record RetrievalVO(
        List<EvidenceVO> evidence,
        String requestedStrategy,
        String strategy,
        String rerankStatus,
        List<ChannelStateVO> channels,
        int channelCandidates,
        int fusionCandidates,
        int topK,
        boolean refillLimitReached,
        Double minRerankScore,
        String status,
        String outcome,
        RetrievalTraceVO trace, boolean candidateBudgetTruncated) {}
