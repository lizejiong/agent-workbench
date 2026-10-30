package com.lzj.rag.ai.text.controller.request;

import jakarta.validation.constraints.NotNull;

/**
 * 文本工具接口的输入，允许空字符串，但不接受 null。
 *
 * @param text 文本工具输入或输出内容；空字符串有效，输入不接受 null。
 */
public record TextRequest(@NotNull String text) {}
