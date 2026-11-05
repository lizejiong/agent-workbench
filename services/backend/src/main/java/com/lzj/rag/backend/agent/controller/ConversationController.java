package com.lzj.rag.backend.agent.controller;

import com.lzj.rag.backend.agent.controller.AgentContracts.*;
import com.lzj.rag.backend.agent.repository.ConversationLedger;
import com.lzj.rag.backend.rag.service.impl.DelegationService;
import com.lzj.rag.backend.security.model.KnowledgeBasePrincipal;
import com.lzj.rag.framework.starter.web.result.Results;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController @Validated
@RequestMapping("/api/conversations")
public class ConversationController {
    private final ConversationLedger ledger;private final DelegationService grants;
    public ConversationController(ConversationLedger ledger,DelegationService grants){this.ledger=ledger;this.grants=grants;}
    @PostMapping public ResponseEntity<?> create(@AuthenticationPrincipal KnowledgeBasePrincipal actor,@Valid @RequestBody Create input) {
        return reply(201,ledger.create(actor,input));
    }
    @GetMapping public ResponseEntity<?> list(@AuthenticationPrincipal KnowledgeBasePrincipal actor,
            @RequestParam(defaultValue="1") @Min(1) @Max(10000) int current,@RequestParam(defaultValue="30") @Min(1) @Max(100) int size,
            @RequestParam(required=false) @Size(max=120) String search){return reply(200,ledger.list(actor,current,size,search));}
    @GetMapping("/{id}") public ResponseEntity<?> get(@AuthenticationPrincipal KnowledgeBasePrincipal actor,@PathVariable UUID id){return reply(200,ledger.require(actor,id,false));}
    @PatchMapping("/{id}") public ResponseEntity<?> rename(@AuthenticationPrincipal KnowledgeBasePrincipal actor,@PathVariable UUID id,@Valid @RequestBody Rename input){return reply(200,ledger.rename(actor,id,input.title()));}
    @DeleteMapping("/{id}") public ResponseEntity<?> delete(@AuthenticationPrincipal KnowledgeBasePrincipal actor,@PathVariable UUID id){ledger.delete(actor,id);return ResponseEntity.noContent().header("Cache-Control","no-store").build();}
    @GetMapping("/{id}/messages") public ResponseEntity<?> messages(@AuthenticationPrincipal KnowledgeBasePrincipal actor,@PathVariable UUID id,
            @RequestParam(defaultValue="9223372036854775807") @Min(1) long beforeSequence,@RequestParam(defaultValue="30") @Min(1) @Max(100) int size){return reply(200,ledger.messages(actor,id,beforeSequence,size));}
    @PostMapping("/{id}/delegations") public ResponseEntity<?> delegate(@AuthenticationPrincipal KnowledgeBasePrincipal actor,@PathVariable UUID id,@Valid @RequestBody Delegate input) {
        var c=ledger.require(actor,id,false);
        return reply(201,grants.issue(actor,input.serviceId(),id,c.kbIds(),c.documentIds()));
    }
    @PostMapping("/{id}/runs/{runId}/cancel") public ResponseEntity<?> cancel(@AuthenticationPrincipal KnowledgeBasePrincipal actor,@PathVariable UUID id,@PathVariable UUID runId){return reply(200,ledger.cancel(actor,id,runId));}
    @GetMapping("/{id}/runs/{runId}") public ResponseEntity<?> run(@AuthenticationPrincipal KnowledgeBasePrincipal actor,@PathVariable UUID id,@PathVariable UUID runId){return reply(200,ledger.result(actor,id,runId));}
    private ResponseEntity<?> reply(int code,Object data){return ResponseEntity.status(code).header("Cache-Control","no-store").body(Results.success(data));}
}
