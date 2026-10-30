package com.lzj.rag.ai.model.service.bo;

/**
 * 业务层 Token 用量，保留上游未报告计数的未知状态。
 *
 * @param inputTokens 供应商报告的输入 Token 数，未报告时为 null。
 * @param outputTokens 供应商报告的输出 Token 数，未报告时为 null。
 * @param totalTokens 供应商报告的总 Token 数，未报告时为 null。
 */
public record UsageBO(Long inputTokens, Long outputTokens, Long totalTokens) {}
