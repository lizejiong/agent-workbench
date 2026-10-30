package com.lzj.rag.backend.model.service;

import com.lzj.rag.backend.model.service.bo.EmbeddingBindingBO;
import com.lzj.rag.backend.model.service.bo.EmbeddingResultBO;

import java.time.Instant;
import java.util.List;

/** 嵌入能力契约，要求整批向量使用同一模型身份和 AI 配置修订。 */
public interface EmbeddingClient {

    /**
     * 按固定模型绑定生成有序向量，所有输入共用同一 AI 配置修订。
     *
     * @param binding 本次业务固定使用的嵌入模型身份与配置快照。
     * @param texts 本次需要计算嵌入的有序文本集合。
     * @param requestId 请求跟踪标识或业务幂等键，按当前接口约定使用。
     * @param deadline 本阶段或本次请求必须完成的截止时间。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    EmbeddingResultBO embed(
            EmbeddingBindingBO binding, List<String> texts, String requestId, Instant deadline);
}
