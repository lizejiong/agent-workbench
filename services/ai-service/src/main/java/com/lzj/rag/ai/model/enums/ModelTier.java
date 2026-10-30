package com.lzj.rag.ai.model.enums;

/** 模型路由档位，只控制候选优先级，不隐式开启思考模式。 */
public enum ModelTier {

    // 优先使用面向低时延或较低成本配置的候选顺序。
    FAST,
    // 使用默认均衡档位配置的候选顺序。
    STANDARD,
    // 优先使用面向较强能力配置的候选顺序。
    STRONG
}
