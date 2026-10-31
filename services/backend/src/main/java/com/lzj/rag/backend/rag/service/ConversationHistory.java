package com.lzj.rag.backend.rag.service;

import com.lzj.rag.backend.security.model.KnowledgeBasePrincipal;
import java.util.*;

public interface ConversationHistory {
    List<Map<String, String>> recent(KnowledgeBasePrincipal actor, UUID conversationId);
}
