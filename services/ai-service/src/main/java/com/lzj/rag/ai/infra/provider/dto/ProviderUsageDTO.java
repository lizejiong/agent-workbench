package com.lzj.rag.ai.infra.provider.dto;

/**
 * 供应商实际返回的 Token 计数；未知字段保留 null。
 *
 * @param inputTokens 供应商报告的输入 Token 数，未报告时为 null。
 * @param outputTokens 供应商报告的输出 Token 数，未报告时为 null。
 * @param totalTokens 供应商报告的总 Token 数，未报告时为 null。
 */
public record ProviderUsageDTO(Long inputTokens, Long outputTokens, Long totalTokens) {}
