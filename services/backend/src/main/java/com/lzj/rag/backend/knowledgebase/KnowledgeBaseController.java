package com.lzj.rag.backend.knowledgebase;

import com.lzj.rag.backend.security.KnowledgeBasePrincipal;
import com.lzj.rag.framework.convention.page.PageResponse;
import com.lzj.rag.framework.convention.result.Result;
import com.lzj.rag.framework.starter.web.result.Results;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

import static com.lzj.rag.backend.knowledgebase.KnowledgeBaseTypes.*;

@RestController
@RequestMapping({"/api/kbs", "/api/v1/knowledge-bases"})
public class KnowledgeBaseController {
    private final KnowledgeBaseService service;

    public KnowledgeBaseController(KnowledgeBaseService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<Result<View>> create(@AuthenticationPrincipal KnowledgeBasePrincipal actor,
                                               @Valid @RequestBody CreateRequest request) {
        var view = service.create(actor, request);
        return ResponseEntity.created(URI.create("/api/kbs/" + view.id())).body(Results.success(view));
    }

    @GetMapping
    public Result<PageResponse<View>> list(@AuthenticationPrincipal KnowledgeBasePrincipal actor,
                                          @RequestParam(defaultValue = "1") @Min(1) @Max(1000000) int current,
                                          @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
                                          @RequestParam(defaultValue = "") @Size(max = 128) @jakarta.validation.constraints.Pattern(regexp = "[^\\x00]*") String name) {
        return Results.success(service.list(actor, current, size, name));
    }

    @GetMapping("/{id}")
    public Result<View> get(@AuthenticationPrincipal KnowledgeBasePrincipal actor, @PathVariable UUID id) {
        return Results.success(service.get(actor, id));
    }

    @PutMapping("/{id}")
    public Result<View> update(@AuthenticationPrincipal KnowledgeBasePrincipal actor, @PathVariable UUID id,
                               @Valid @RequestBody UpdateRequest request) {
        return Results.success(service.update(actor, id, request));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@AuthenticationPrincipal KnowledgeBasePrincipal actor, @PathVariable UUID id,
                               @RequestParam @Min(1) long version) {
        service.delete(actor, id, version);
        return Results.success();
    }

    @PatchMapping("/{id}")
    public Result<View> patch(@AuthenticationPrincipal KnowledgeBasePrincipal actor, @PathVariable UUID id,
                              @Valid @RequestBody PatchRequest request) {
        return Results.success(service.patch(actor, id, request));
    }
    @PutMapping("/{id}/embedding-binding")
    public Result<View> bind(@AuthenticationPrincipal KnowledgeBasePrincipal actor, @PathVariable UUID id,
                             @Valid @RequestBody BindRequest request) {
        return Results.success(service.bindLegacy(actor, id, request));
    }
}
