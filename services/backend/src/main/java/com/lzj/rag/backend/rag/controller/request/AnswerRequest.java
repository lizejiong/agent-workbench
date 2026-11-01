package com.lzj.rag.backend.rag.controller.request;

import com.lzj.rag.backend.rag.service.bo.RagRetrieveBO;
import com.lzj.rag.backend.search.service.bo.SearchBO;
import jakarta.validation.constraints.*;
import java.util.*;

public record AnswerRequest(@NotBlank @Size(max=16000) @Pattern(regexp="[^\\x00]*") String question,UUID conversationId,
        @Size(max=64) List<@NotNull UUID> kbIds,@Size(min=1,max=500) List<@NotNull UUID> documentIds,
        @Min(1) @Max(200) Integer topK,Double minScore,@Pattern(regexp="AUTO|VECTOR|HYBRID|KEYWORD") String strategy,Boolean rerank,Double minRerankScore) {
    public RagRetrieveBO toBO(){return new RagRetrieveBO(new SearchBO(question,kbIds,documentIds,topK,minScore,strategy,rerank,minRerankScore),conversationId);}
}
