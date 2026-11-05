package com.lzj.rag.backend.agent.controller;

import com.lzj.rag.backend.agent.controller.AgentContracts.*;
import com.lzj.rag.backend.agent.repository.ConversationLedger;
import com.lzj.rag.backend.knowledgebase.exception.KnowledgeBaseException;
import com.lzj.rag.backend.rag.service.bo.DelegationBO;
import com.lzj.rag.backend.rag.service.impl.DelegationService;
import com.lzj.rag.backend.security.config.InternalRagSecurityConfiguration.ServiceIdentity;
import com.lzj.rag.backend.security.context.RagTraceContext;
import com.lzj.rag.framework.starter.web.result.Results;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/internal/agent/conversations/{id}")
public class InternalConversationController {
    private final ConversationLedger ledger;private final DelegationService grants;
    public InternalConversationController(ConversationLedger ledger,DelegationService grants){this.ledger=ledger;this.grants=grants;}
    @PostMapping("/runs") public ResponseEntity<?> begin(@AuthenticationPrincipal ServiceIdentity service,@PathVariable UUID id,@Valid @RequestBody Begin input,HttpServletRequest request) {
        var g=grant(service,request);String trace=RagTraceContext.child(request.getHeader("traceparent")).substring(3,35);
        return reply(ledger.begin(g,id,input,trace));
    }
    @GetMapping("/history") public ResponseEntity<?> history(@AuthenticationPrincipal ServiceIdentity service,@PathVariable UUID id,HttpServletRequest request){return reply(ledger.history(grant(service,request),id));}
    @GetMapping("/runs/{runId}") public ResponseEntity<?> get(@AuthenticationPrincipal ServiceIdentity service,@PathVariable UUID id,@PathVariable UUID runId,HttpServletRequest request){return reply(ledger.result(grant(service,request),id,runId));}
    @PatchMapping("/runs/{runId}") public ResponseEntity<?> checkpoint(@AuthenticationPrincipal ServiceIdentity service,@PathVariable UUID id,@PathVariable UUID runId,@Valid @RequestBody Checkpoint input,HttpServletRequest request){return reply(ledger.checkpoint(grant(service,request),id,runId,input));}
    private DelegationBO grant(ServiceIdentity service,HttpServletRequest request) {
        if(Collections.list(request.getHeaders("X-Rag-Delegation")).size()>1)throw invalid();
        var grant=grants.require(service.serviceId(),request.getHeader("X-Rag-Delegation"));
        if(Collections.list(request.getHeaders("X-Rag-Deadline")).size()!=1)throw invalid();
        Instant deadline;try{deadline=Instant.parse(request.getHeader("X-Rag-Deadline"));}catch(Exception e){throw invalid();}
        if(!deadline.isAfter(Instant.now()) || deadline.isAfter(Instant.now().plusSeconds(125)))throw invalid();
        return grant;
    }
    private KnowledgeBaseException invalid(){return new KnowledgeBaseException(400,"AGENT_INTERNAL_METADATA","Invalid internal conversation metadata");}
    private ResponseEntity<?> reply(Object data){return ResponseEntity.ok().header("Cache-Control","no-store").body(Results.success(data));}
}
