package com.lzj.rag.backend.rag.service;

import com.lzj.rag.backend.rag.service.bo.*;
import com.lzj.rag.backend.security.model.KnowledgeBasePrincipal;
import java.time.Instant;

public interface RagRetrievalService {
    enum QueryMode { USER, INDEPENDENT }
    RagRetrievalBO retrieve(KnowledgeBasePrincipal actor, RagRetrieveBO request, String requestId, QueryMode mode, Instant deadline);
}
