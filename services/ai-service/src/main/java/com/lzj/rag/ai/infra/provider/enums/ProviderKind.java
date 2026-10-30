package com.lzj.rag.ai.infra.provider.enums;

/** 供应商协议类型，用于选择推理开关、重排结构和跟踪头的适配方式。 */
public enum ProviderKind {

    // 百炼供应商，使用其兼容模式对话与原生重排协议。
    BAILIAN,
    // DeepSeek 供应商，思考模式使用 thinking.type 结构。
    DEEPSEEK,
    // 硅基流动供应商，支持兼容模式及扁平重排协议。
    SILICONFLOW
}
