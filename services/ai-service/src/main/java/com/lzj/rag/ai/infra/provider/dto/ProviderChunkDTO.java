package com.lzj.rag.ai.infra.provider.dto;

/**
 * 从供应商 SSE 帧解析出的单个增量片段。
 *
 * @param type 流片段或业务事件类型，如 reasoning、content、tool、usage、done、error。
 * @param text 正文或推理的增量文本，工具和用量片段可为空。
 * @param tool 当前工具定义或工具调用业务数据。
 * @param usage 供应商报告的真实用量；缺失时保持 null。
 * @param finishReason 供应商明确返回的完成原因，不能根据连接关闭自行推断。
 */
public record ProviderChunkDTO(
        String type,
        String text,
        ProviderToolCallDTO tool,
        ProviderUsageDTO usage,
        String finishReason) {}
