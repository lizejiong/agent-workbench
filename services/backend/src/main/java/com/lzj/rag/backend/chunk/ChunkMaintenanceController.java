package com.lzj.rag.backend.chunk;

import com.lzj.rag.backend.document.*;
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
import java.util.*;
import static com.lzj.rag.backend.chunk.MaintenanceTypes.*;

@RestController
public class ChunkMaintenanceController {
    private final ChunkMaintenanceService service;private final DocumentCleanupService cleanup;
    public ChunkMaintenanceController(ChunkMaintenanceService service,DocumentCleanupService cleanup){this.service=service;this.cleanup=cleanup;}
    @GetMapping("/api/documents/{documentId}/chunks")
    public Result<PageResponse<ChunkView>> list(@AuthenticationPrincipal KnowledgeBasePrincipal actor,@PathVariable UUID documentId,
            @RequestParam(defaultValue="1") @Min(1) @Max(1000000) int current,@RequestParam(defaultValue="20") @Min(1) @Max(100) int size,
            @RequestParam(required=false) Boolean enabled){return Results.success(service.list(actor,documentId,current,size,enabled));}
    @PostMapping("/api/documents/{documentId}/chunks")
    public ResponseEntity<Result<Changed>> create(@AuthenticationPrincipal KnowledgeBasePrincipal actor,@PathVariable UUID documentId,@Valid @RequestBody ContentRequest request) {
        var result=service.save(actor,documentId,null,request);return ResponseEntity.status(201).body(Results.success(result));
    }
    @PatchMapping("/api/documents/{documentId}/chunks/{chunkId}")
    public Result<Changed> edit(@AuthenticationPrincipal KnowledgeBasePrincipal actor,@PathVariable UUID documentId,@PathVariable UUID chunkId,@Valid @RequestBody ContentRequest request) {
        return Results.success(service.save(actor,documentId,chunkId,request));
    }
    @DeleteMapping("/api/documents/{documentId}/chunks/{chunkId}")
    public Result<Changed> delete(@AuthenticationPrincipal KnowledgeBasePrincipal actor,@PathVariable UUID documentId,@PathVariable UUID chunkId,@RequestParam @Positive long version) {
        return Results.success(service.delete(actor,documentId,chunkId,version));
    }
    @PatchMapping("/api/documents/{documentId}/chunks/{chunkId}/enabled")
    public Result<Changed> enabled(@AuthenticationPrincipal KnowledgeBasePrincipal actor,@PathVariable UUID documentId,@PathVariable UUID chunkId,@Valid @RequestBody EnabledRequest request) {
        return Results.success(service.enabled(actor,documentId,List.of(chunkId),false,request.enabled(),request.version()));
    }
    @PostMapping("/api/documents/{documentId}/chunks/batch-enabled")
    public Result<Changed> batch(@AuthenticationPrincipal KnowledgeBasePrincipal actor,@PathVariable UUID documentId,@Valid @RequestBody BatchRequest request) {
        return Results.success(service.enabled(actor,documentId,request.chunkIds(),request.applyAll(),request.enabled(),request.version()));
    }
    @PatchMapping("/api/documents/{documentId}/enabled")
    public Result<DocumentTypes.View> documentEnabled(@AuthenticationPrincipal KnowledgeBasePrincipal actor,@PathVariable UUID documentId,@Valid @RequestBody EnabledRequest request) {
        return Results.success(service.documentEnabled(actor,documentId,request.enabled(),request.version()));
    }
    @DeleteMapping("/api/documents/{documentId}")
    public ResponseEntity<Result<DocumentCleanupService.View>> documentDelete(@AuthenticationPrincipal KnowledgeBasePrincipal actor,@PathVariable UUID documentId,@RequestParam @Positive long version) {
        var result=cleanup.delete(actor,documentId,version);
        return ResponseEntity.accepted().location(URI.create("/api/document-cleanups/"+result.id())).body(Results.success(result));
    }
    @GetMapping("/api/document-cleanups/{id}")
    public Result<DocumentCleanupService.View> cleanup(@AuthenticationPrincipal KnowledgeBasePrincipal actor,@PathVariable UUID id){return Results.success(cleanup.get(actor,id));}
    @PostMapping("/api/document-cleanups/{id}/retry")
    public Result<DocumentCleanupService.View> retry(@AuthenticationPrincipal KnowledgeBasePrincipal actor,@PathVariable UUID id){return Results.success(cleanup.retry(actor,id));}
}
