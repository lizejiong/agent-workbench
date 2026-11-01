package com.lzj.rag.backend.rag.controller.request;

import com.lzj.rag.backend.search.service.bo.SearchBO;
import jakarta.validation.constraints.*;
import java.util.*;

/** 不接受身份、会话、queryMode、模型或截止时间字段。 */
public record InternalRetrieveRequest(@NotBlank @Size(max=16000) @Pattern(regexp="[^\\x00]*") String query,
        @Size(max=64) List<@NotNull UUID> kbIds,@Size(min=1,max=500) List<@NotNull UUID> documentIds,
        @Min(1) @Max(200) Integer topK,Double minScore,@Pattern(regexp="AUTO|VECTOR|KEYWORD|HYBRID") String strategy,
        Boolean rerank,Double minRerankScore) {
    public SearchBO search(){return new SearchBO(query,kbIds,documentIds,topK,minScore,strategy,rerank,minRerankScore);}
}
