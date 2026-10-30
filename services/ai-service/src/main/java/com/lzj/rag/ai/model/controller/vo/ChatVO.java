package com.lzj.rag.ai.model.controller.vo;

import java.util.List;

/**
 * 对话接口返回对象，区分正文、推理文本、工具调用与统计信息。
 *
 * @param content 模型返回的正文内容，与推理文本分开保存。
 * @param reasoning 模型独立返回的推理内容，也可用于工具回合续接。
 * @param toolCalls 当前消息或结果包含的工具调用列表，流式场景按 index 拼接。
 * @param model 本次实际使用或请求固定的逻辑模型别名。
 * @param finishReason 供应商明确返回的完成原因，不能根据连接关闭自行推断。
 * @param usage 供应商报告的真实用量；缺失时保持 null。
 * @param metrics 一次逻辑请求的尝试次数和耗时统计。
 */
public record ChatVO(
        String content,
        String reasoning,
        List<ToolCallVO> toolCalls,
        String model,
        String finishReason,
        UsageVO usage,
        MetricsVO metrics) {

    /**
     * 对外返回的工具调用；流式返回时允许只包含增量字段。
     *
     * @param id 供应商工具调用 ID，用于关联后续工具结果。
     * @param name 工具函数名称或当前具名配置的标识。
     * @param arguments 工具调用参数的 JSON 字符串；流式场景可能只是一个增量片段。
     * @param index 同一轮工具调用的零起始位置，增量根据此值累加。
     */
    public record ToolCallVO(String id, String name, String arguments, Integer index) {}
}
