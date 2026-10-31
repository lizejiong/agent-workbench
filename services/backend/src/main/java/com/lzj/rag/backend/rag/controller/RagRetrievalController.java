package com.lzj.rag.backend.rag.controller;

import com.lzj.rag.backend.rag.controller.request.RagRetrieveRequest;
import com.lzj.rag.backend.rag.controller.vo.RagRetrievalVO;
import com.lzj.rag.backend.rag.service.RagRetrievalService;
import com.lzj.rag.backend.rag.service.bo.RagRetrieveBO;
import com.lzj.rag.backend.search.config.SearchProperties;
import com.lzj.rag.backend.search.service.bo.SearchBO;
import com.lzj.rag.backend.security.model.KnowledgeBasePrincipal;
import com.lzj.rag.framework.convention.result.Result;
import com.lzj.rag.framework.starter.web.context.RequestIdContext;
import com.lzj.rag.framework.starter.web.result.Results;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;

@RestController
public class RagRetrievalController {
    private final RagRetrievalService service;
    private final SearchProperties limits;
    public RagRetrievalController(RagRetrievalService service,SearchProperties limits) { this.service=service;this.limits=limits; }
    @PostMapping("/api/rag/retrieve")
    public ResponseEntity<Result<RagRetrievalVO>> retrieve(@AuthenticationPrincipal KnowledgeBasePrincipal actor,
                                                        @Valid @RequestBody RagRetrieveRequest r) {
        var search=new SearchBO(r.query(),r.kbIds(),r.documentIds(),r.topK(),r.minScore(),r.strategy(),r.rerank(),r.minRerankScore());
        var result=service.retrieve(actor,new RagRetrieveBO(search,r.conversationId()),RequestIdContext.get(),
                RagRetrievalService.QueryMode.USER,Instant.now().plus(limits.deadline()));
        return ResponseEntity.ok().header("Cache-Control","no-store").body(Results.success(RagRetrievalVO.from(result)));
    }
}
