package com.lzj.rag.ai.model.service.bo;

/**
 * 供应商片段转换后的业务数据，用于判断可见输出和流结束条件。
 *
 * @param type 流片段或业务事件类型，如 reasoning、content、tool、usage、done、error。
 * @param text 正文或推理的增量文本，工具和用量片段可为空。
 * @param tool 当前工具定义或工具调用业务数据。
 * @param usage 供应商报告的真实用量；缺失时保持 null。
 * @param finishReason 供应商明确返回的完成原因，不能根据连接关闭自行推断。
 */
public record ModelChunkBO(
        String type, String text, ToolCallBO tool, UsageBO usage, String finishReason) {

    /**
     * 判断片段是否已有可见正文、推理或工具内容；空角色帧和心跳不能触发首片段完成。
     *
     * @return 包含可见内容时为 true，单个空白字符也算有效文本。
     */
    public boolean meaningful() {
        return ((type.equals("content") || type.equals("reasoning"))
                        && text != null
                        && !text.isEmpty())
                || (tool != null
                        && (nonempty(tool.id())
                                || nonempty(tool.name())
                                || nonempty(tool.arguments())));
    }

    /**
     * 检查字符串是否至少有一个字符，不去掉有意义的空白增量。
     *
     * @param value 当前待检查或转换的单个值。
     * @return 非 null 且长度大于零时为 true。
     */
    private static boolean nonempty(String value) {
        return value != null && !value.isEmpty();
    }
}
