package com.lzj.rag.backend.refresh;
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
import static com.lzj.rag.backend.refresh.RemoteTypes.*;

@RestController
public class RemoteController {
    private final UrlImportService imports;private final RefreshService refresh;
    public RemoteController(UrlImportService imports,RefreshService refresh){this.imports=imports;this.refresh=refresh;}
    @PostMapping("/api/kbs/{kbId}/documents/import-url")
    public ResponseEntity<Result<Imported>> importUrl(@AuthenticationPrincipal KnowledgeBasePrincipal actor,@PathVariable UUID kbId,
            @RequestHeader("Idempotency-Key") String key,@Valid @RequestBody ImportRequest request) {
        var result=imports.importUrl(actor,kbId,key,request);
        return ResponseEntity.status(result.replayed()?200:request.ingest()?202:201).location(URI.create("/api/documents/"+result.document().id()))
                .header("Idempotency-Replayed",Boolean.toString(result.replayed())).body(Results.success(result));
    }
    @PutMapping("/api/documents/{id}/refresh-schedule")
    public Result<ScheduleView> schedule(@AuthenticationPrincipal KnowledgeBasePrincipal actor,@PathVariable UUID id,@Valid @RequestBody ScheduleRequest request){return Results.success(refresh.configure(actor,id,request));}
    @GetMapping("/api/documents/{id}/refresh-schedule")
    public Result<ScheduleView> schedule(@AuthenticationPrincipal KnowledgeBasePrincipal actor,@PathVariable UUID id){return Results.success(refresh.schedule(actor,id));}
    @PostMapping("/api/documents/{id}/refresh")
    public ResponseEntity<Result<RunView>> refresh(@AuthenticationPrincipal KnowledgeBasePrincipal actor,@PathVariable UUID id,@Valid @RequestBody RefreshRequest request) {
        var result=refresh.submit(actor,id,request);return ResponseEntity.status(result.replayed()?200:202).header("Idempotency-Replayed",Boolean.toString(result.replayed())).body(Results.success(result.run()));
    }
    @GetMapping("/api/documents/{id}/refresh-runs")
    public Result<PageResponse<RunView>> runs(@AuthenticationPrincipal KnowledgeBasePrincipal actor,@PathVariable UUID id,
            @RequestParam(defaultValue="1") @Min(1) @Max(1000000) int current,@RequestParam(defaultValue="20") @Min(1) @Max(100) int size){return Results.success(refresh.list(actor,id,current,size));}
}
