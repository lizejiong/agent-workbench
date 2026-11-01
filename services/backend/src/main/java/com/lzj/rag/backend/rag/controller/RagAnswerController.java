package com.lzj.rag.backend.rag.controller;

import com.lzj.rag.backend.rag.controller.request.AnswerRequest;
import com.lzj.rag.backend.rag.controller.vo.AnswerVO;
import com.lzj.rag.backend.rag.service.RagAnswerService;
import com.lzj.rag.backend.rag.service.impl.AnswerQueryService;
import com.lzj.rag.backend.rag.service.bo.*;
import com.lzj.rag.backend.security.model.KnowledgeBasePrincipal;
import com.lzj.rag.framework.convention.result.Result;
import com.lzj.rag.framework.starter.web.context.RequestIdContext;
import com.lzj.rag.framework.starter.web.result.Results;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.*;

@RestController
public class RagAnswerController {
    private final RagAnswerService answers;private final AnswerQueryService queries;
    public RagAnswerController(RagAnswerService answers,AnswerQueryService queries){this.answers=answers;this.queries=queries;}
    @PostMapping("/api/rag/answer")
    public ResponseEntity<Result<?>> answer(@AuthenticationPrincipal KnowledgeBasePrincipal actor,@Valid @RequestBody AnswerRequest request) {
        try {return ResponseEntity.ok().header("Cache-Control","no-store").body(Results.success(AnswerVO.from(answers.answer(actor,request.toBO(),RequestIdContext.get()))));}
        catch(AnswerFailure failure) {
            var run=failure.run();
            var body=Result.failure(failure.code(),failure.getMessage()).setRequestId(RequestIdContext.get())
                    .setData(Map.of("answerId",run.id(),"conversationId",run.conversationId(),"requestId",run.requestId()));
            return ResponseEntity.status(failure.status()).header("Cache-Control","no-store").body(body);
        }
    }
    public record RunVO(UUID answerId,UUID conversationId,String question,String status,String answer,AnswerVO result,String errorCode,Instant createdAt,Instant updatedAt) {
        static RunVO from(AnswerRunBO r){return new RunVO(r.id(),r.conversationId(),r.question(),r.status(),r.answerText(),AnswerVO.from(r.result()),r.errorCode(),r.createdAt(),r.updatedAt());}
    }
    @GetMapping("/api/rag/runs/{id}")
    public ResponseEntity<Result<RunVO>> run(@AuthenticationPrincipal KnowledgeBasePrincipal actor,@PathVariable UUID id) {
        return ResponseEntity.ok().header("Cache-Control","no-store").body(Results.success(RunVO.from(queries.get(actor,id))));
    }
    @GetMapping("/api/rag/conversations/{id}")
    public ResponseEntity<Result<List<RunVO>>> conversation(@AuthenticationPrincipal KnowledgeBasePrincipal actor,@PathVariable UUID id) {
        return ResponseEntity.ok().header("Cache-Control","no-store").body(Results.success(queries.history(actor,id).stream().map(RunVO::from).toList()));
    }
}
