package com.lzj.rag.ai.text.service;

import com.lzj.rag.ai.text.service.bo.TokenEstimateBO;

/** 独立于 HTTP 的文本工具业务契约。 */
public interface TextService {

    /**
     * 按汉字与其他 Unicode 码点的粗略比例估算 Token 数，不用于计费或强制截断。
     *
     * @param text 文本工具输入或输出内容；空字符串有效，输入不接受 null。
     * @return 包含估算数量和 estimated=true 的业务结果。
     */
    TokenEstimateBO estimateTokens(String text);

    /**
     * 只移除覆盖整个 trimmed 文本的一层完整代码围栏；混合正文和多个代码块保持原文。
     *
     * @param text 文本工具输入或输出内容；空字符串有效，输入不接受 null。
     * @return 围栏内部内容，或不满足完整包围条件时的原文。
     */
    String stripCodeFence(String text);
}
