package com.lzj.rag.backend.search;

import com.lzj.rag.backend.security.KnowledgeBasePrincipal;
import com.lzj.rag.framework.convention.result.Result;
import com.lzj.rag.framework.starter.web.result.Results;
import com.lzj.rag.framework.starter.web.context.RequestIdContext;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;

@RestController
public class KnowledgeSearchController {
    private final HybridRetrievalService search;private final HybridProperties config;private final KeywordProperties keyword;
    public KnowledgeSearchController(HybridRetrievalService search,HybridProperties config,KeywordProperties keyword){this.search=search;this.config=config;this.keyword=keyword;}
    @PostMapping("/api/knowledge/search")
    public ResponseEntity<Result<List<KnowledgeSearchTypes.Evidence>>> search(@AuthenticationPrincipal KnowledgeBasePrincipal actor,
            @Valid @RequestBody KnowledgeSearchTypes.Request request) {
        var result=search.retrieve(actor,request,RequestIdContext.get());
        return ResponseEntity.ok().header("X-KB-Search-Strategy",result.strategy()).header("X-KB-Rerank-Status",result.rerankStatus())
                .header("X-KB-Channel-Status",result.channels().stream().map(c->c.channel()+":"+c.status()).reduce((a,b)->a+","+b).orElse(""))
                .body(Results.success(result.evidence()));
    }
    @PostMapping("/api/knowledge/retrieve")
    public Result<KnowledgeSearchTypes.Retrieval> retrieve(@AuthenticationPrincipal KnowledgeBasePrincipal actor,@Valid @RequestBody KnowledgeSearchTypes.Request request){return Results.success(search.retrieve(actor,request,RequestIdContext.get()));}
    @GetMapping("/api/knowledge/search-capabilities")
    public Result<Map<String,Object>> capabilities(){return Results.success(Map.of("defaultStrategy",config.strategy(),"keywordConfigured",keyword.enabled(),"keywordAnalyzer",keyword.analyzer(),"keywordScoreType","BM25","rerankConfigured",config.rerank().enabled(),"rerankModel",config.rerank().model(),"channelCandidates",config.channelCandidates(),"fusionCandidates",config.fusionCandidates(),"answerEnabled",false));}
}
