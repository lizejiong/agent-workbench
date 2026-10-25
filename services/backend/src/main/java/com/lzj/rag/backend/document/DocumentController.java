package com.lzj.rag.backend.document;

import com.lzj.rag.backend.security.KnowledgeBasePrincipal;
import com.lzj.rag.backend.storage.ObjectStorageClient;
import com.lzj.rag.framework.convention.page.PageResponse;
import com.lzj.rag.framework.convention.result.Result;
import com.lzj.rag.framework.starter.web.result.Results;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static com.lzj.rag.backend.document.DocumentTypes.*;

@RestController
public class DocumentController {
    private final DocumentService service;
    private final UploadOperations operations;
    private final ObjectStorageClient storage;
    public DocumentController(DocumentService service, UploadOperations operations, ObjectStorageClient storage) {
        this.service = service; this.operations = operations; this.storage = storage;
    }
    @PostMapping(value = "/api/kbs/{kbId}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Result<View>> upload(@AuthenticationPrincipal KnowledgeBasePrincipal actor, @PathVariable UUID kbId,
            @RequestPart MultipartFile file, @RequestPart(required = false) String metadata,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        var uploaded = service.upload(actor, kbId, file, metadata, idempotencyKey);
        return ResponseEntity.status(uploaded.replayed() ? 200 : 201)
                .location(URI.create("/api/documents/" + uploaded.document().id()))
                .header("Idempotency-Replayed", Boolean.toString(uploaded.replayed())).body(Results.success(uploaded.document()));
    }
    @GetMapping("/api/kbs/{kbId}/documents")
    public Result<PageResponse<View>> list(@AuthenticationPrincipal KnowledgeBasePrincipal actor, @PathVariable UUID kbId,
            @RequestParam(defaultValue = "1") @Min(1) @Max(1000000) int current,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "") @Size(max = 255) @Pattern(regexp = "[^\\x00]*") String name,
            @RequestParam(defaultValue = "") @Size(max = 32) String status) {
        return Results.success(service.list(actor, kbId, current, size, name, status));
    }
    @GetMapping("/api/documents/{id}")
    public Result<View> get(@AuthenticationPrincipal KnowledgeBasePrincipal actor, @PathVariable UUID id) {
        return Results.success(service.get(actor, id));
    }
    @PatchMapping("/api/documents/{id}")
    public Result<View> patch(@AuthenticationPrincipal KnowledgeBasePrincipal actor, @PathVariable UUID id,
                              @Valid @RequestBody PatchRequest request) {
        return Results.success(service.patch(actor, id, request));
    }
    @GetMapping("/api/documents/{id}/file")
    public ResponseEntity<InputStreamResource> file(@AuthenticationPrincipal KnowledgeBasePrincipal actor, @PathVariable UUID id) {
        var doc = service.requireOwned(actor, id);
        // Authorization precedes storage access; Spring closes the resource after streaming.
        var stream = storage.open(doc.getObjectKey());
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(doc.getMimeType())).contentLength(doc.getFileSize())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(doc.getOriginalFilename(), StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store").header("X-Content-Type-Options", "nosniff")
                .body(new InputStreamResource(stream));
    }
    @GetMapping("/api/kbs/{kbId}/storage-operations")
    public Result<PageResponse<UploadOperations.View>> operations(@AuthenticationPrincipal KnowledgeBasePrincipal actor,
            @PathVariable UUID kbId, @RequestParam(defaultValue = "1") @Min(1) @Max(1000000) int current,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return Results.success(operations.list(actor, kbId, current, size));
    }
    @PostMapping("/api/kbs/{kbId}/storage-operations/{id}/retry-cleanup")
    public Result<UploadOperations.View> retry(@AuthenticationPrincipal KnowledgeBasePrincipal actor, @PathVariable UUID kbId,
                                               @PathVariable UUID id) {
        return Results.success(operations.retryCleanup(actor, kbId, id));
    }
}
