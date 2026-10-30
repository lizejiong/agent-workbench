package com.lzj.rag.ai.model.enums;

/** 模型声明支持的能力，用于在调用前过滤不兼容候选。 */
public enum ModelCapability {

    // 普通对话能力。
    CHAT,
    // 包含图片输入的视觉理解能力。
    VISION,
    // 可显式控制原生思考模式的能力。
    THINKING,
    // 工具定义、工具调用与工具结果续接能力。
    TOOLS,
    // 供应商原生 JSON 对象输出能力。
    JSON,
    // 文本转向量能力。
    EMBEDDING,
    // 文档相关性重排能力。
    RERANK
}
