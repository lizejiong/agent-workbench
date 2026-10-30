package com.lzj.rag.ai.model.controller.vo;

/**
 * 对外展示的实际 Token 用量，不用估算值替代未知计数。
 *
 * @param inputTokens 供应商报告的输入 Token 数，未报告时为 null。
 * @param outputTokens 供应商报告的输出 Token 数，未报告时为 null。
 * @param totalTokens 供应商报告的总 Token 数，未报告时为 null。
 */
public record UsageVO(Long inputTokens, Long outputTokens, Long totalTokens) {}
