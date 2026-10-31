package com.lzj.rag.backend.model.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 复用 AI 服务的 chat 协议；消息和模型由可信 Java 服务构造。 */
public interface ChatClient {
    JsonNode chat(List<Map<String, String>> messages, String model, boolean structured,
                  int maxOutputTokens, String requestId, Instant deadline);
}
