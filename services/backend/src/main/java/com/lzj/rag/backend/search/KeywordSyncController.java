package com.lzj.rag.backend.search;
import com.lzj.rag.backend.security.KnowledgeBasePrincipal;
import com.lzj.rag.framework.convention.page.PageResponse;
import com.lzj.rag.framework.convention.result.Result;
import com.lzj.rag.framework.starter.web.result.Results;
import jakarta.validation.constraints.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
public class KeywordSyncController {
    private final KeywordSyncService sync;
    public KeywordSyncController(KeywordSyncService sync){this.sync=sync;}
    @GetMapping("/api/documents/{id}/keyword-index") public Result<KeywordSyncService.View> get(@AuthenticationPrincipal KnowledgeBasePrincipal actor,@PathVariable UUID id){return Results.success(sync.get(actor,id));}
    @GetMapping("/api/kbs/{kbId}/keyword-sync") public Result<PageResponse<KeywordSyncService.View>> list(@AuthenticationPrincipal KnowledgeBasePrincipal actor,@PathVariable UUID kbId,@RequestParam(defaultValue="1") @Min(1) @Max(1000000) int current,@RequestParam(defaultValue="20") @Min(1) @Max(100) int size){return Results.success(sync.list(actor,kbId,current,size));}
    @PostMapping("/api/kbs/{kbId}/keyword-sync/{id}/retry") public Result<KeywordSyncService.View> retry(@AuthenticationPrincipal KnowledgeBasePrincipal actor,@PathVariable UUID kbId,@PathVariable UUID id){return Results.success(sync.retry(actor,kbId,id));}
}
