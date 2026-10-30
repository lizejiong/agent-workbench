package com.lzj.rag.ai.model.service;

import com.lzj.rag.ai.model.service.bo.ChatBO;
import com.lzj.rag.ai.model.service.bo.ChatResultBO;
import com.lzj.rag.ai.model.service.bo.EmbedBO;
import com.lzj.rag.ai.model.service.bo.EmbedResultBO;
import com.lzj.rag.ai.model.service.bo.EmbeddingModelBO;
import com.lzj.rag.ai.model.service.bo.ModelEventBO;
import com.lzj.rag.ai.model.service.bo.RerankBO;
import com.lzj.rag.ai.model.service.bo.RerankResultBO;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** 可被多个入口复用的模型业务契约；响应式返回值不包含 HTTP 或 SSE 类型。 */
public interface ModelService {

    /**
     * 查询可公开的嵌入模型描述与配置修订，不执行付费供应商探测。
     *
     * @param alias 应用内部的逻辑模型别名，不是供应商原生模型名。
     * @param dimensions 本次向量结果使用的维度。
     * @return 与目标维度对应的模型元数据。
     */
    EmbeddingModelBO embeddingModel(String alias, Integer dimensions);

    /**
     * 校验输入后按有序候选执行对话，在共享预算内降级，并返回完整业务结果。
     *
     * @param input 当前待转换或发送的输入数据。
     * @param vision 是否按视觉请求校验图片并要求候选具备视觉能力。
     * @param requestId 贯穿接口、业务调用、供应商请求与日志的关联 ID。
     * @return 包含正文、工具、用量与调用统计的业务结果。
     */
    Mono<ChatResultBO> chat(ChatBO input, boolean vision, String requestId);

    /**
     * 执行流式模型调用；可见输出前允许降级，输出后只发送本次模型的结束或错误事件。
     *
     * @param input 当前待转换或发送的输入数据。
     * @param vision 是否按视觉请求校验图片并要求候选具备视觉能力。
     * @param requestId 贯穿接口、业务调用、供应商请求与日志的关联 ID。
     * @return 可被取消的业务事件流。
     */
    Flux<ModelEventBO> stream(ChatBO input, boolean vision, String requestId);

    /**
     * 固定向量身份，按批次调用候选；只有整组完成并通过校验才返回结果。
     *
     * @param input 当前待转换或发送的输入数据。
     * @param requestId 贯穿接口、业务调用、供应商请求与日志的关联 ID。
     * @return 按原始输入顺序排列、且身份一致的完整向量结果。
     */
    Mono<EmbedResultBO> embed(EmbedBO input, String requestId);

    /**
     * 校验文档身份与输入限制，一次重排整组文档，再稳定排序并截取结果。
     *
     * @param input 当前待转换或发送的输入数据。
     * @param requestId 贯穿接口、业务调用、供应商请求与日志的关联 ID。
     * @return 按分数降序、同分按原始位置升序的结果。
     */
    Mono<RerankResultBO> rerank(RerankBO input, String requestId);
}
