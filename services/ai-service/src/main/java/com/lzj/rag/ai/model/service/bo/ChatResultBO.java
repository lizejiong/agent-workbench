package com.lzj.rag.ai.model.service.bo;

import java.util.List;

/**
 * 完成路由与结果校验后的对话业务结果。
 *
 * @param content 模型返回的正文内容，与推理文本分开保存。
 * @param reasoning 模型独立返回的推理内容，也可用于工具回合续接。
 * @param toolCalls 当前消息或结果包含的工具调用列表，流式场景按 index 拼接。
 * @param model 本次实际使用或请求固定的逻辑模型别名。
 * @param finishReason 供应商明确返回的完成原因，不能根据连接关闭自行推断。
 * @param usage 供应商报告的真实用量；缺失时保持 null。
 * @param metrics 一次逻辑请求的尝试次数和耗时统计。
 */
public record ChatResultBO(
        String content,
        String reasoning,
        List<ToolCallBO> toolCalls,
        String model,
        String finishReason,
        UsageBO usage,
        MetricsBO metrics) {}
