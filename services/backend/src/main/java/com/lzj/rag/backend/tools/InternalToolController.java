package com.lzj.rag.backend.tools;

import com.lzj.rag.backend.rag.service.impl.DelegationService;
import com.lzj.rag.backend.rag.service.bo.DelegationBO;
import com.lzj.rag.backend.security.config.InternalRagSecurityConfiguration.ServiceIdentity;
import com.lzj.rag.framework.starter.web.result.Results;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/internal/tools/runs/{runId}")
public class InternalToolController {
    final DelegationService grants;final ToolLedger calls;final ToolCatalog catalog;
    public InternalToolController(DelegationService grants,ToolLedger calls,ToolCatalog catalog){this.grants=grants;this.calls=calls;this.catalog=catalog;}
    @GetMapping("/tools") public ResponseEntity<?> tools(@AuthenticationPrincipal ServiceIdentity service,@PathVariable UUID runId,HttpServletRequest req){return reply(calls.tools(grant(service,req),runId));}
    @PostMapping("/calls") public ResponseEntity<?> start(@AuthenticationPrincipal ServiceIdentity service,@PathVariable UUID runId,@Valid @RequestBody ToolContracts.Start body,HttpServletRequest req){return reply(calls.start(grant(service,req),runId,body));}
    @PostMapping("/calls/{callId}/authorize") public ResponseEntity<?> authorize(@AuthenticationPrincipal ServiceIdentity service,@PathVariable UUID runId,@PathVariable String callId,HttpServletRequest req){calls.authorize(grant(service,req),runId,callId);return reply(Map.of("allowed",true));}
    @GetMapping("/calls/{callId}/connection") public ResponseEntity<?> connection(@AuthenticationPrincipal ServiceIdentity service,@PathVariable UUID runId,@PathVariable String callId,HttpServletRequest req){return reply(calls.connection(grant(service,req),runId,callId));}
    @PutMapping("/calls/{callId}") public ResponseEntity<?> finish(@AuthenticationPrincipal ServiceIdentity service,@PathVariable UUID runId,@PathVariable String callId,@Valid @RequestBody ToolContracts.Finish body,HttpServletRequest req){return reply(calls.finish(grant(service,req),runId,callId,body));}
    @PostMapping("/evidence") public ResponseEntity<?> evidence(@AuthenticationPrincipal ServiceIdentity service,@PathVariable UUID runId,@Valid @RequestBody ToolContracts.Source body,HttpServletRequest req){return reply(calls.readEvidence(grant(service,req),runId,body.evidenceId()));}
    @PostMapping("/validate") public ResponseEntity<?> validate(@AuthenticationPrincipal ServiceIdentity service,@PathVariable UUID runId,@Valid @RequestBody ToolContracts.Validate body,HttpServletRequest req){calls.validate(grant(service,req),runId,body.sourceIds());return reply(Map.of("valid",true));}
    @PostMapping("/history-dependencies") public ResponseEntity<?> history(@AuthenticationPrincipal ServiceIdentity service,@PathVariable UUID runId,@Valid @RequestBody ToolContracts.History body,HttpServletRequest req){calls.inherit(grant(service,req),runId,body.messageIds());return reply(Map.of("bound",true));}
    DelegationBO grant(ServiceIdentity service,HttpServletRequest req){
        if(Collections.list(req.getHeaders("X-Rag-Delegation")).size()!=1 || Collections.list(req.getHeaders("X-Rag-Deadline")).size()!=1)throw catalog.invalid();
        try{var deadline=Instant.parse(req.getHeader("X-Rag-Deadline"));if(!deadline.isAfter(Instant.now()) || deadline.isAfter(Instant.now().plusSeconds(125)))throw catalog.invalid();}
        catch(Exception e){throw catalog.invalid();}
        return grants.require(service.serviceId(),req.getHeader("X-Rag-Delegation"));
    }
    ResponseEntity<?> reply(Object data){return ResponseEntity.ok().header("Cache-Control","no-store").body(Results.success(data));}
}
