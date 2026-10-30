package com.lzj.rag.backend.model.service;

import com.lzj.rag.backend.model.service.bo.RerankInputBO;
import com.lzj.rag.backend.model.service.bo.RerankScoreBO;

import java.time.Instant;
import java.util.*;

/** 文档重排能力契约，使用输入位置将分数对应回原切片。 */
public interface RerankClient {

    /**
     * 在给定截止时间内对输入文档评分，返回每个原始输入位置的分数。
     *
     * @param query 检索查询文本或已生成的查询向量。
     * @param candidates 本次待领取的任务、可选解析策略或召回候选集合。
     * @param requestId 请求跟踪标识或业务幂等键，按当前接口约定使用。
     * @param deadline 本阶段或本次请求必须完成的截止时间。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    List<RerankScoreBO> rerank(
            String query, List<RerankInputBO> candidates, String requestId, Instant deadline);
}
