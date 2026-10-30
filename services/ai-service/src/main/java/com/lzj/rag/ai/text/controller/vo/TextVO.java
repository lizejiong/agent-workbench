package com.lzj.rag.ai.text.controller.vo;

/**
 * 文本处理接口的返回对象。
 *
 * @param text 文本工具输入或输出内容；空字符串有效，输入不接受 null。
 */
public record TextVO(String text) {}
