package com.lzj.rag.ai.text.service.bo;

/**
 * 粗略 Token 估算业务结果，始终标记为估算值。
 *
 * @param tokens 按粗略字符比例计算的 Token 估算数量。
 * @param estimated 是否为估算值；文本工具结果固定为 true。
 */
public record TokenEstimateBO(long tokens, boolean estimated) {}
