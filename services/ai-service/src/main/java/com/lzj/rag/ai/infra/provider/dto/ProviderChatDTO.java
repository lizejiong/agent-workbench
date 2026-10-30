package com.lzj.rag.ai.infra.provider.dto;

import java.util.List;

/**
 * 供应商单次对话的归一化结果，尚未附加业务路由和耗时信息。
 *
 * @param content 模型返回的正文内容，与推理文本分开保存。
 * @param reasoning 模型独立返回的推理内容，也可用于工具回合续接。
 * @param tools 模型实际请求执行的工具调用列表。
 * @param finish 供应商明确返回的本轮完成原因。
 * @param usage 供应商报告的真实用量；缺失时保持 null。
 */
public record ProviderChatDTO(
        String content,
        String reasoning,
        List<ProviderToolCallDTO> tools,
        String finish,
        ProviderUsageDTO usage) {}
