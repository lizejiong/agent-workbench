package com.lzj.rag.backend.tools;

import com.lzj.rag.backend.security.model.KnowledgeBasePrincipal;
import com.lzj.rag.framework.starter.web.result.Results;
import jakarta.validation.Valid;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/tools")
@EnableConfigurationProperties(ToolProperties.class)
public class ToolAdminController {
    final ToolCatalog catalog;final ToolLedger calls;
    public ToolAdminController(ToolCatalog catalog,ToolLedger calls){this.catalog=catalog;this.calls=calls;}
    @GetMapping("/servers") public ResponseEntity<?> servers(@AuthenticationPrincipal KnowledgeBasePrincipal actor){return reply(catalog.servers(actor));}
    @PostMapping("/servers") public ResponseEntity<?> create(@AuthenticationPrincipal KnowledgeBasePrincipal actor,@Valid @RequestBody ToolContracts.Server body){return reply(catalog.save(actor,null,body));}
    @PutMapping("/servers/{id}") public ResponseEntity<?> update(@AuthenticationPrincipal KnowledgeBasePrincipal actor,@PathVariable UUID id,@Valid @RequestBody ToolContracts.Server body){return reply(catalog.save(actor,id,body));}
    @PostMapping("/servers/{id}/test") public ResponseEntity<?> test(@AuthenticationPrincipal KnowledgeBasePrincipal actor,@PathVariable UUID id){return reply(catalog.discover(actor,id,false));}
    @PostMapping("/servers/{id}/discover") public ResponseEntity<?> discover(@AuthenticationPrincipal KnowledgeBasePrincipal actor,@PathVariable UUID id){return reply(catalog.discover(actor,id,true));}
    @GetMapping("/servers/{id}/tools") public ResponseEntity<?> tools(@AuthenticationPrincipal KnowledgeBasePrincipal actor,@PathVariable UUID id){return reply(catalog.definitions(actor,id));}
    @PutMapping("/publish") public ResponseEntity<?> publish(@AuthenticationPrincipal KnowledgeBasePrincipal actor,@RequestParam String toolKey,@Valid @RequestBody ToolContracts.Publish body){return reply(catalog.publish(actor,toolKey,body));}
    @PutMapping("/grant") public ResponseEntity<?> grant(@AuthenticationPrincipal KnowledgeBasePrincipal actor,@RequestParam String toolKey,@Valid @RequestBody ToolContracts.Grant body){catalog.grant(actor,toolKey,body);return reply(Map.of("updated",true));}
    @GetMapping("/calls") public ResponseEntity<?> calls(@AuthenticationPrincipal KnowledgeBasePrincipal actor,@RequestParam(required=false) UUID runId,@RequestParam(defaultValue="50") int limit){if(limit<1 || limit>100)throw catalog.invalid();return reply(calls.records(actor,runId,limit));}
    private ResponseEntity<?> reply(Object data){return ResponseEntity.ok().header("Cache-Control","no-store").body(Results.success(data));}
}
