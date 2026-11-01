package com.lzj.rag.backend.rag.controller;

import com.lzj.rag.backend.rag.service.impl.DelegationService;
import com.lzj.rag.backend.security.model.KnowledgeBasePrincipal;
import com.lzj.rag.framework.starter.web.result.Results;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
public class RagDelegationController {
    public record Request(@NotBlank @Pattern(regexp="[a-z0-9-]{1,80}") String serviceId,@NotNull UUID agentSessionId,
            @Size(max=64) List<@NotNull UUID> kbIds,@Size(min=1,max=500) List<@NotNull UUID> documentIds) {}
    private final DelegationService grants;
    public RagDelegationController(DelegationService grants){this.grants=grants;}
    @PostMapping("/api/rag/delegations")
    public ResponseEntity<?> issue(@AuthenticationPrincipal KnowledgeBasePrincipal actor,@Valid @RequestBody Request request) {
        return ResponseEntity.status(201).header("Cache-Control","no-store").body(Results.success(grants.issue(actor,request.serviceId(),request.agentSessionId(),request.kbIds(),request.documentIds())));
    }
    @DeleteMapping("/api/rag/delegations/{id}")
    public ResponseEntity<?> revoke(@AuthenticationPrincipal KnowledgeBasePrincipal actor,@PathVariable UUID id) {
        grants.revoke(actor,id);return ResponseEntity.noContent().header("Cache-Control","no-store").build();
    }
}
