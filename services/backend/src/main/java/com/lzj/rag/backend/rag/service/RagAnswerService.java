package com.lzj.rag.backend.rag.service;

import com.lzj.rag.backend.rag.service.bo.*;
import com.lzj.rag.backend.security.model.KnowledgeBasePrincipal;
import java.util.UUID;

public interface RagAnswerService {
    RagAnswerBO answer(KnowledgeBasePrincipal actor,RagRetrieveBO request,String requestId);
    AnswerTask start(KnowledgeBasePrincipal actor,RagRetrieveBO request,String requestId,boolean streaming,AnswerEvents events);
    AnswerRunBO cancel(KnowledgeBasePrincipal actor,UUID id);
    void disconnect(AnswerTask task);
    void timeout(AnswerTask task);
}
