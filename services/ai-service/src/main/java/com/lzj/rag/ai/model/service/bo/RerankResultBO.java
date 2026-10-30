package com.lzj.rag.ai.model.service.bo;

import java.util.List;

/**
 * 完成索引校验、排序和截取后的重排业务结果。
 *
 * @param results 供应商原生协议中的重排结果数组。
 * @param model 本次实际使用或请求固定的逻辑模型别名。
 * @param usage 供应商报告的真实用量；缺失时保持 null。
 * @param metrics 一次逻辑请求的尝试次数和耗时统计。
 */
public record RerankResultBO(
        List<RankedBO> results, String model, UsageBO usage, MetricsBO metrics) {

    /**
     * 保留文档 ID 和原始索引的重排业务数据。
     *
     * @param chunkId 调用方提供的文档或片段 ID，用于关联原业务数据。
     * @param index 结果在当前输入列表中的零起始位置，用于恢复原顺序。
     * @param score 当前文档的原始位置与相关性分数。
     */
    public record RankedBO(String chunkId, int index, double score) {}
}
