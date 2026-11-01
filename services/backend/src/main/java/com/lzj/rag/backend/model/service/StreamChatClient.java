package com.lzj.rag.backend.model.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;

/** 只转发正文增量；完成后返回实际模型、用量和结束原因。 */
public interface StreamChatClient {
    interface Cancellation {
        void check();
        void register(Runnable cancel);
        void unregister(Runnable cancel);
        default void startedModel(String model) {}
    }
    JsonNode stream(List<Map<String,String>> messages,String model,int maxOutputTokens,String requestId,
                    Instant deadline,Cancellation cancellation,Consumer<String> text);
}
