package com.lzj.rag.backend.search.controller.vo;

import java.util.*;

/**
 * 检索策略、耗时、通道及模型身份的追踪信息。
 *
 * @param requestId 请求跟踪标识或业务幂等键，按当前接口约定使用。
 * @param pipelineVersion 生成切片的入库流水线版本。
 * @param elapsedMs 本次检索已消耗的毫秒数。
 * @param indexes 本次检索使用的知识库索引版本快照。
 * @param keywordIndexVersion 本次关键词索引身份标记。
 * @param rerankModel 重排模型的公开逻辑标识。
 */
public record RetrievalTraceVO(
        String requestId,
        String pipelineVersion,
        long elapsedMs,
        List<IndexVersionVO> indexes,
        String keywordIndexVersion,
        String rerankModel) {}
