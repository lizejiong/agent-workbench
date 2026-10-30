package com.lzj.rag.ai.model.service.bo;

/**
 * 一次逻辑请求的业务统计，覆盖所有候选尝试。
 *
 * @param attempts 当前逻辑请求已经实际发起的候选调用次数。
 * @param durationMs 整个逻辑请求经过的毫秒数，包含此前失败的候选尝试。
 * @param firstTokenMs 逻辑请求到首个有效输出片段的毫秒数；非流式或尚未输出时为 null。
 */
public record MetricsBO(int attempts, long durationMs, Long firstTokenMs) {}
