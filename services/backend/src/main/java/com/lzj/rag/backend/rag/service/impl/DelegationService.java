package com.lzj.rag.backend.rag.service.impl;

import com.lzj.rag.backend.knowledgebase.exception.KnowledgeBaseException;
import com.lzj.rag.backend.model.catalog.EmbeddingCatalog;
import com.lzj.rag.backend.rag.config.InternalRagProperties;
import com.lzj.rag.backend.rag.repository.DelegationRepository;
import com.lzj.rag.backend.rag.service.bo.DelegationBO;
import com.lzj.rag.backend.search.config.SearchProperties;
import com.lzj.rag.backend.search.service.HybridRetrievalService;
import com.lzj.rag.backend.search.service.bo.SearchBO;
import com.lzj.rag.backend.security.model.KnowledgeBasePrincipal;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Service;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;

@Service
public class DelegationService {
    public record Issued(UUID delegationId,String delegationToken,String serviceId,UUID agentSessionId,
                         String tenantId,String userId,List<UUID> kbIds,List<UUID> documentIds,Instant expiresAt) {}
    private final DelegationRepository grants;
    private final HybridRetrievalService engine;
    private final UserDetailsService users;
    private final InternalRagProperties config;
    private final SearchProperties limits;
    private final SecureRandom random=new SecureRandom();
    public DelegationService(DelegationRepository grants,HybridRetrievalService engine,UserDetailsService users,InternalRagProperties config,SearchProperties limits) {
        this.grants=grants;this.engine=engine;this.users=users;this.config=config;this.limits=limits;
    }
    public Issued issue(KnowledgeBasePrincipal actor,String service,UUID session,List<UUID> kbs,List<UUID> documents) {
        if(!config.enabled())throw new KnowledgeBaseException(503,"RAG_INTERNAL_DISABLED","Internal retrieval is disabled");
        if(!config.serviceTokenHashes().containsKey(service))throw new KnowledgeBaseException(400,"RAG_SERVICE_UNKNOWN","Unknown delegate service");
        // 复用原范围解析与全范围授权；此步骤不做向量化或召回。
        var scope=engine.prepare(actor,new SearchBO("?",kbs,documents,1,null),Instant.now().plus(limits.deadline()));
        byte[] secret=new byte[32];random.nextBytes(secret);String token="rd_"+Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        UUID id=UUID.randomUUID();Instant expiry=Instant.now().plus(config.delegationTtl());
        grants.insert(new DelegationRepository.Grant(id,service,actor.getUsername(),actor.tenantId(),actor.userId(),session,scope.kbIds(),scope.documentIds(),expiry),EmbeddingCatalog.sha256(token));
        return new Issued(id,token,service,session,actor.tenantId(),actor.userId(),scope.kbIds(),scope.documentIds(),expiry);
    }
    public DelegationBO require(String service,String token) {
        if(token==null || !token.matches("rd_[A-Za-z0-9_-]{43}"))throw DelegationRepository.invalid();
        var g=grants.find(service,EmbeddingCatalog.sha256(token));
        KnowledgeBasePrincipal actor;
        try{actor=(KnowledgeBasePrincipal)users.loadUserByUsername(g.username());}catch(Exception missing){throw DelegationRepository.invalid();}
        if(!actor.isEnabled() || !actor.isAccountNonLocked() || !actor.isAccountNonExpired() || !actor.isCredentialsNonExpired()
                || !actor.tenantId().equals(g.tenantId()) || !actor.userId().equals(g.ownerId()))throw DelegationRepository.invalid();
        return new DelegationBO(g.id(),service,g.sessionId(),actor,g.kbIds(),g.documentIds(),g.expiresAt());
    }
    public SearchBO scope(DelegationBO g,SearchBO r) {
        var ids=r.kbIds()==null?g.kbIds():r.kbIds();
        var docs=r.documentIds()==null?g.documentIds():r.documentIds();
        if(!g.kbIds().containsAll(ids) || g.documentIds()!=null && (docs==null || !g.documentIds().containsAll(docs)))
            throw new KnowledgeBaseException(403,"RAG_DELEGATION_SCOPE","Requested scope exceeds the user delegation");
        return new SearchBO(r.query(),ids,docs,r.topK(),r.minScore(),r.strategy(),r.rerank(),r.minRerankScore());
    }
    public void recheck(DelegationBO g){if(!grants.active(g.id()))throw DelegationRepository.invalid();}
    public void revoke(KnowledgeBasePrincipal actor,UUID id){grants.revoke(actor,id);}
}
