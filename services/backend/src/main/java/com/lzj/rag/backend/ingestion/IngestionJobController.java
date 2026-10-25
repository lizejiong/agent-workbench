package com.lzj.rag.backend.ingestion;

import com.lzj.rag.backend.security.KnowledgeBasePrincipal;
import com.lzj.rag.framework.convention.page.PageResponse;
import com.lzj.rag.framework.convention.result.Result;
import com.lzj.rag.framework.starter.web.result.Results;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.UUID;

@RestController
public class IngestionJobController {
    public record SubmitRequest(@NotBlank @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9._:-]{7,127}") String requestId, boolean confirmOverwriteEdited, @Positive Long expectedDocumentVersion) { }
    private final IngestionJobService jobs;
    public IngestionJobController(IngestionJobService jobs){this.jobs=jobs;}
    @PostMapping("/api/documents/{documentId}/ingestion-jobs")
    public ResponseEntity<Result<IngestionJobService.JobView>> submit(@AuthenticationPrincipal KnowledgeBasePrincipal actor,@PathVariable UUID documentId,
            @Valid @RequestBody SubmitRequest request){return submitted(jobs.submit(actor,documentId,request.requestId(),null,request.confirmOverwriteEdited(),request.expectedDocumentVersion()));}
    @GetMapping("/api/ingestion-jobs/{jobId}")
    public Result<IngestionJobService.JobView> get(@AuthenticationPrincipal KnowledgeBasePrincipal actor,@PathVariable UUID jobId){return Results.success(jobs.getOwnedJob(actor,jobId));}
    @GetMapping("/api/ingestion-jobs/{jobId}/stages")
    public Result<PageResponse<IngestionJobService.StageView>> stages(@AuthenticationPrincipal KnowledgeBasePrincipal actor,@PathVariable UUID jobId,
            @RequestParam(defaultValue="1") @Min(1) @Max(1000000) int current,@RequestParam(defaultValue="20") @Min(1) @Max(100) int size) {
        var job=jobs.getOwnedJob(actor,jobId);return Results.success(jobs.stages(actor,job.documentId(),jobId,current,size));
    }
    @PostMapping("/api/ingestion-jobs/{jobId}/retry")
    public ResponseEntity<Result<IngestionJobService.JobView>> retry(@AuthenticationPrincipal KnowledgeBasePrincipal actor,@PathVariable UUID jobId,
            @Valid @RequestBody SubmitRequest request) {
        var previous=jobs.getOwnedJob(actor,jobId);return submitted(jobs.submit(actor,previous.documentId(),request.requestId(),jobId,request.confirmOverwriteEdited(),request.expectedDocumentVersion()));
    }
    @PostMapping("/api/ingestion-jobs/{jobId}/cancel")
    public Result<IngestionJobService.JobView> cancel(@AuthenticationPrincipal KnowledgeBasePrincipal actor,@PathVariable UUID jobId){return Results.success(jobs.cancel(actor,jobId));}
    private ResponseEntity<Result<IngestionJobService.JobView>> submitted(IngestionJobService.Submission submission) {
        return ResponseEntity.status(submission.replayed()?200:202).location(URI.create("/api/ingestion-jobs/"+submission.job().id()))
                .header("Idempotency-Replayed",Boolean.toString(submission.replayed())).body(Results.success(submission.job()));
    }
}
