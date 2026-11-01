package com.lzj.rag.backend.rag.controller;

import com.lzj.rag.backend.knowledgebase.exception.KnowledgeBaseException;
import com.lzj.rag.backend.rag.controller.request.InternalRetrieveRequest;
import com.lzj.rag.backend.rag.controller.support.InternalRetrievalExecution;
import com.lzj.rag.backend.rag.service.bo.DelegationBO;
import com.lzj.rag.backend.rag.service.impl.*;
import com.lzj.rag.backend.search.config.SearchProperties;
import com.lzj.rag.backend.security.config.InternalRagSecurityConfiguration.ServiceIdentity;
import com.lzj.rag.backend.security.context.RagTraceContext;
import com.lzj.rag.framework.starter.web.context.RequestIdContext;
import com.lzj.rag.framework.starter.web.result.Results;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.async.DeferredResult;
import java.time.Instant;
import java.util.Collections;

@RestController
public class InternalRagController {
    private final DelegationService grants;private final InternalRetrievalExecution retrieval;private final SearchProperties limits;
    public InternalRagController(DelegationService grants,InternalRetrievalExecution retrieval,SearchProperties limits){this.grants=grants;this.retrieval=retrieval;this.limits=limits;}
    @PostMapping("/internal/rag/retrieve")
    public DeferredResult<ResponseEntity<?>> retrieve(@AuthenticationPrincipal ServiceIdentity service,@Valid @RequestBody InternalRetrieveRequest input,
            HttpServletRequest request,HttpServletResponse response) {
        String id=header(request,"X-Request-Id");
        if(!id.equals(RequestIdContext.get()))throw invalid("RAG_REQUEST_ID_INVALID");
        var grant=grants.require(service.serviceId(),header(request,"X-Rag-Delegation"));
        String trace=RagTraceContext.child(request.getHeader("traceparent"));response.setHeader("traceparent",trace);
        return retrieval.retrieve(grant,input.search(),id,trace,deadline(request,grant));
    }
    @PostMapping("/internal/rag/requests/{requestId}/cancel")
    public ResponseEntity<?> cancel(@AuthenticationPrincipal ServiceIdentity service,@PathVariable String requestId,HttpServletRequest request) {
        if(!requestId.matches("[A-Za-z0-9._:-]{1,128}"))throw invalid("RAG_REQUEST_ID_INVALID");
        var grant=grants.require(service.serviceId(),header(request,"X-Rag-Delegation"));
        retrieval.cancel(grant,requestId,RagTraceContext.child(request.getHeader("traceparent")),deadline(request,grant));
        return ResponseEntity.accepted().header("Cache-Control","no-store").body(Results.success());
    }
    private Instant deadline(HttpServletRequest request,DelegationBO grant) {
        Instant supplied;try{supplied=Instant.parse(header(request,"X-Rag-Deadline"));}catch(Exception e){throw invalid("RAG_DEADLINE_INVALID");}
        Instant now=Instant.now(),cap=now.plus(limits.deadline());
        if(!supplied.isAfter(now))throw new KnowledgeBaseException(504,"RAG_TOOL_TIMEOUT","Retrieval deadline expired");
        if(grant.expiresAt().isBefore(cap))cap=grant.expiresAt();return supplied.isBefore(cap)?supplied:cap;
    }
    private String header(HttpServletRequest request,String name) {
        var values=Collections.list(request.getHeaders(name));
        if(values.size()!=1 || values.get(0).isBlank())throw invalid("RAG_INTERNAL_HEADERS");return values.get(0);
    }
    private KnowledgeBaseException invalid(String code){return new KnowledgeBaseException(400,code,"Invalid internal retrieval metadata");}
}
