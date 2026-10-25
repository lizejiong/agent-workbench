package com.lzj.rag.backend.model;

import com.lzj.rag.backend.parser.FormatProperties;
import com.lzj.rag.backend.knowledgebase.KnowledgeBaseException;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.*;

@Component
public class AiVlmClient implements VlmClient {
    private final AiEmbeddingClient transport;private final FormatProperties config;
    public AiVlmClient(AiEmbeddingClient transport,FormatProperties config){this.transport=transport;this.config=config;}
    public Description describe(byte[] image,String mime,String requestId,Instant deadline) {
        if(!config.vlm().enabled())throw new KnowledgeBaseException(503,"KB_VLM_UNAVAILABLE","Image parsing requires configured kb.formats.vlm and the existing AI vision service");
        if(image.length==0 || image.length>config.maxImageBytes())throw new KnowledgeBaseException(422,"KB_IMAGE_LIMIT","Image exceeds configured byte limit");
        var message=Map.of("role","user","content","请忠实描述图片中的可见内容，并逐行转写可辨识的文字。区分描述和文字转写；无法辨识时明确说明，不推断缺失内容。",
                "images",List.of(Map.of("base64",Base64.getEncoder().encodeToString(image),"mediaType",mime)));
        var body=Map.<String,Object>of("messages",List.of(message),"task","vision","preferredModel",config.vlm().model(),"thinking",false,"maxOutputTokens",config.vlm().maxOutputTokens());
        var result=transport.visionCall(body,requestId,deadline);String text=result.path("content").asText("").strip();
        if(!config.vlm().model().equals(result.path("model").asText()))throw new KnowledgeBaseException(502,"KB_VLM_MODEL_CHANGED","Vision response used a different model; no fallback result is accepted");
        if(text.isBlank() || text.codePoints().anyMatch(c->c==0 || (c>=0xd800 && c<=0xdfff)) || "length".equals(result.path("finishReason").asText()))
            throw new KnowledgeBaseException(502,"KB_VLM_INVALID_RESPONSE","Vision description is empty, invalid or truncated");
        return new Description(text,config.vlm().model());
    }
}
