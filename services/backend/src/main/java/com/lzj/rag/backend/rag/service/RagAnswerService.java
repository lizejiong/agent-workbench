package com.lzj.rag.backend.rag.service;

import com.lzj.rag.backend.rag.service.bo.*;
import com.lzj.rag.backend.security.model.KnowledgeBasePrincipal;

public interface RagAnswerService {
    RagAnswerBO answer(KnowledgeBasePrincipal actor,RagRetrieveBO request,String requestId);
}
