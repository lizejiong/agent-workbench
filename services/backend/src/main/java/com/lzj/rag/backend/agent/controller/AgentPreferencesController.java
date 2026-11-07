package com.lzj.rag.backend.agent.controller;

import com.lzj.rag.backend.agent.repository.AgentPreferences;
import com.lzj.rag.backend.security.model.KnowledgeBasePrincipal;
import com.lzj.rag.framework.starter.web.result.Results;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/agent/preferences")
public class AgentPreferencesController {
    private final AgentPreferences preferences;
    public AgentPreferencesController(AgentPreferences preferences){this.preferences=preferences;}
    @GetMapping public ResponseEntity<?> get(@AuthenticationPrincipal KnowledgeBasePrincipal actor){return reply(preferences.get(actor));}
    @PutMapping public ResponseEntity<?> save(@AuthenticationPrincipal KnowledgeBasePrincipal actor,@Valid @RequestBody PreferenceContracts.Write input){return reply(preferences.save(actor,input));}
    @PostMapping("/reset") public ResponseEntity<?> reset(@AuthenticationPrincipal KnowledgeBasePrincipal actor,@Valid @RequestBody PreferenceContracts.Reset input){return reply(preferences.reset(actor,input));}
    private ResponseEntity<?> reply(Object data){return ResponseEntity.ok().header("Cache-Control","no-store").body(Results.success(data));}
}
