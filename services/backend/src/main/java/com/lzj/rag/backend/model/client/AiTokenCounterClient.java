package com.lzj.rag.backend.model.client;

import com.lzj.rag.backend.model.service.TokenCounterClient;
import com.lzj.rag.backend.knowledgebase.exception.KnowledgeBaseException;
import org.springframework.stereotype.Component;
import java.time.Instant;

/** 沿用 AI 文本工具的估算协议，明确 estimated，不冒充模型专用 tokenizer。 */
@Component
public class AiTokenCounterClient implements TokenCounterClient {
    private final AiEmbeddingClient transport;
    public AiTokenCounterClient(AiEmbeddingClient transport){this.transport=transport;}
    public Estimate estimate(String text,String requestId,Instant deadline) {
        var result=transport.tokenEstimateCall(text,requestId,deadline);
        if(!result.path("tokens").isIntegralNumber() || !result.path("tokens").canConvertToLong()
                || result.path("tokens").asLong()<0 || result.path("tokens").asLong()==0 && !text.isBlank()
                || !result.path("estimated").isBoolean())
            throw new KnowledgeBaseException(502,"RAG_TOKEN_COUNT_INVALID","Token estimate is invalid");
        return new Estimate(result.path("tokens").asLong(),result.path("estimated").asBoolean());
    }
}
