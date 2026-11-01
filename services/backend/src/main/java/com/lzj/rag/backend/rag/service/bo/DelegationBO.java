package com.lzj.rag.backend.rag.service.bo;

import com.lzj.rag.backend.security.model.KnowledgeBasePrincipal;
import java.time.Instant;
import java.util.*;

public record DelegationBO(UUID id,String serviceId,UUID agentSessionId,KnowledgeBasePrincipal actor,
        List<UUID> kbIds,List<UUID> documentIds,Instant expiresAt) {}
