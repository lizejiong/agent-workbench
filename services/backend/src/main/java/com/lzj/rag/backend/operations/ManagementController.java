package com.lzj.rag.backend.operations;

import com.fasterxml.jackson.databind.*;
import com.lzj.rag.backend.document.DocumentTypes;
import com.lzj.rag.backend.knowledgebase.KnowledgeBaseService;
import com.lzj.rag.backend.security.KnowledgeBasePrincipal;
import com.lzj.rag.framework.convention.page.PageResponse;
import com.lzj.rag.framework.convention.result.Result;
import com.lzj.rag.framework.starter.web.result.Results;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.*;
import java.time.OffsetDateTime;
import java.util.*;

/** API management entry for this repository without a frontend. Never publishes internal service endpoints. */
@RestController
public class ManagementController {
    private final JdbcTemplate jdbc;private final KnowledgeBaseService bases;private final ObjectMapper json;
    public ManagementController(JdbcTemplate jdbc,KnowledgeBaseService bases,ObjectMapper json){this.jdbc=jdbc;this.bases=bases;this.json=json;}
    @GetMapping("/api/knowledge/management")
    public Result<Map<String,Object>> entry() {
        return Results.success(Map.of(
                "delivery","API_ONLY","openapi","/openapi/knowledge-base.yaml","collection","/openapi/knowledge-base.postman_collection.json",
                "capabilities","/api/knowledge/search-capabilities","knowledgeBases","/api/kbs",
                "ingestionDefaults",DocumentTypes.IngestionSpec.defaults(),
                "workflow",List.of("Create KB using a server model profile","Upload or import URL","Submit durable job and poll job/stages",
                        "Retrieve evidence and open its authorized fileUrl","Maintain chunks using current document version","Configure explicit cron/timezone for URL documents"),
                "polling",Map.of("activeIntervalSeconds",2,"hiddenIntervalSeconds",30,"terminalStates",List.of("SUCCEEDED","FAILED","CANCELLED")),
                "conflicts",Map.of("version","409 KB_VERSION_CONFLICT: reload version before retry",
                        "manualOverwrite","409 KB_MANUAL_OVERWRITE_REQUIRED: review changes, send confirmOverwriteEdited=true and expectedDocumentVersion",
                        "activeJob","409 KB_JOB_ACTIVE / KB_REFRESH_ACTIVE: inspect existing work")));
    }
    public record Audit(UUID id,UUID documentId,String actorId,String action,JsonNode chunkIds,JsonNode beforeSummary,JsonNode afterSummary,OffsetDateTime createdAt){ }
    @GetMapping("/api/kbs/{kbId}/audit")
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,timeout=10)
    public Result<PageResponse<Audit>> audit(@AuthenticationPrincipal KnowledgeBasePrincipal actor,@PathVariable UUID kbId,
            @RequestParam(defaultValue="1") @Min(1) @Max(1000000) int current,@RequestParam(defaultValue="20") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue="") @Pattern(regexp="[A-Z_]{0,64}") String action) {
        bases.requireOwned(actor,kbId);
        String filter=" WHERE kb_id=? AND tenant_id=? AND owner_id=? AND (?='' OR action=?)";
        long total=jdbc.queryForObject("SELECT count(*) FROM kb_content_audit"+filter,Long.class,kbId,actor.tenantId(),actor.userId(),action,action);
        var records=jdbc.query("SELECT * FROM kb_content_audit"+filter+" ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",(rs,n)->new Audit(
                rs.getObject("id",UUID.class),rs.getObject("document_id",UUID.class),rs.getString("actor_id"),rs.getString("action"),
                tree(rs.getString("chunk_ids")),tree(rs.getString("before_summary")),tree(rs.getString("after_summary")),rs.getObject("created_at",OffsetDateTime.class)),
                kbId,actor.tenantId(),actor.userId(),action,action,size,((long)current-1)*size);
        return Results.success(new PageResponse<>(current,size,total,records));
    }
    private JsonNode tree(String value){try{return json.readTree(value);}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("Invalid persisted audit summary");}}
}
