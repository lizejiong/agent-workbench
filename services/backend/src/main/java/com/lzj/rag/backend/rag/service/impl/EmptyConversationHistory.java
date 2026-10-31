package com.lzj.rag.backend.rag.service.impl;

import com.lzj.rag.backend.knowledgebase.exception.KnowledgeBaseException;
import com.lzj.rag.backend.rag.service.ConversationHistory;
import com.lzj.rag.backend.security.model.KnowledgeBasePrincipal;
import org.springframework.stereotype.Component;
import java.util.*;

/** 尚无独立问答会话时，未知 ID 按归属资源的 404 契约处理。 */
@Component
public class EmptyConversationHistory implements ConversationHistory {
    public List<Map<String, String>> recent(KnowledgeBasePrincipal actor, UUID id) {
        if (id != null) throw new KnowledgeBaseException(404, "RAG_CONVERSATION_NOT_FOUND", "Conversation not found");
        return List.of();
    }
}
