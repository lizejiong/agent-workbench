package com.lzj.rag.backend.ingestion;

import com.lzj.rag.backend.security.KnowledgeBasePrincipal;
import com.lzj.rag.framework.convention.result.Result;
import com.lzj.rag.framework.convention.page.PageResponse;
import com.lzj.rag.framework.starter.web.result.Results;
import jakarta.validation.constraints.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

/** Document-scoped history; nested detail/stage routes remain compatible with stage 3 clients. */
@RestController
@RequestMapping("/api/documents/{documentId}/ingestion-jobs")
public class IngestionQueryController {
    private final IngestionJobService jobs;
    public IngestionQueryController(IngestionJobService jobs){this.jobs=jobs;}
    @GetMapping
    public Result<PageResponse<IngestionJobService.JobView>> list(@AuthenticationPrincipal KnowledgeBasePrincipal actor,@PathVariable UUID documentId,
            @RequestParam(defaultValue="1") @Min(1) @Max(1000000) int current,@RequestParam(defaultValue="20") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue="") @Size(max=32) String status){return Results.success(jobs.list(actor,documentId,current,size,status));}
    @GetMapping("/{jobId}")
    public Result<IngestionJobService.JobView> get(@AuthenticationPrincipal KnowledgeBasePrincipal actor,@PathVariable UUID documentId,@PathVariable UUID jobId){return Results.success(jobs.getOwnedJob(actor,documentId,jobId));}
    @GetMapping("/{jobId}/stages")
    public Result<PageResponse<IngestionJobService.StageView>> stages(@AuthenticationPrincipal KnowledgeBasePrincipal actor,@PathVariable UUID documentId,@PathVariable UUID jobId,
            @RequestParam(defaultValue="1") @Min(1) @Max(1000000) int current,@RequestParam(defaultValue="20") @Min(1) @Max(100) int size){return Results.success(jobs.stages(actor,documentId,jobId,current,size));}
}
