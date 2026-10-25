package com.lzj.rag.backend.model;

import com.lzj.rag.framework.convention.result.Result;
import com.lzj.rag.framework.starter.web.result.Results;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

@RestController
public class EmbeddingCatalogController {
    private final EmbeddingCatalog catalog;
    public EmbeddingCatalogController(EmbeddingCatalog catalog) { this.catalog = catalog; }
    @GetMapping("/api/kb-models")
    public Result<List<EmbeddingCatalog.Profile>> list() { return Results.success(catalog.available()); }
}
