package com.lzj.rag.ai.text.controller.vo;

/**
 * 粗略 Token 估算响应，不可作为供应商计费依据。
 *
 * @param tokens 按粗略字符比例计算的 Token 估算数量。
 * @param estimated 是否为估算值；文本工具结果固定为 true。
 */
public record TokenEstimateVO(long tokens, boolean estimated) {}
