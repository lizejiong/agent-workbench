package com.lzj.rag.ai.model.service.bo;

/**
 * 与 HTTP 协议无关的模型流事件，由 Controller 转成 SSE。
 *
 * @param type 流片段或业务事件类型，如 reasoning、content、tool、usage、done、error。
 * @param requestId 贯穿接口、业务调用、供应商请求与日志的关联 ID。
 * @param model 本次实际使用或请求固定的逻辑模型别名。
 * @param payload 流事件携带的正文、工具、用量、结束信息或错误信息。
 */
public record ModelEventBO(String type, String requestId, String model, Object payload) {}
