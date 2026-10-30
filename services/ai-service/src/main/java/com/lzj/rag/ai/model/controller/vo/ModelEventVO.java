package com.lzj.rag.ai.model.controller.vo;

/**
 * SSE data 部分的响应结构；事件类型由 SSE event 字段承载。
 *
 * @param requestId 贯穿接口、业务调用、供应商请求与日志的关联 ID。
 * @param model 本次实际使用或请求固定的逻辑模型别名。
 * @param payload 流事件携带的正文、工具、用量、结束信息或错误信息。
 */
public record ModelEventVO(String requestId, String model, Object payload) {}
