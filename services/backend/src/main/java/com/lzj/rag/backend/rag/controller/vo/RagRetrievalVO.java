package com.lzj.rag.backend.rag.controller.vo;

import com.lzj.rag.backend.rag.service.bo.RagRetrievalBO;
import com.lzj.rag.backend.search.controller.vo.RetrievalVO;
import com.lzj.rag.backend.search.converter.SearchWebConverter;
import java.util.*;

public record RagRetrievalVO(String status, QueryVO query, RetrievalVO retrieval) {
    public record QueryVO(String originalQuery, String effectiveQuery, List<String> subQueries,
                          List<UUID> kbIds, boolean needsClarification, String clarificationQuestion,
                          boolean degraded, String fallbackReason, String promptVersion, String model, String termVersion, String promptHash) {}
    public static RagRetrievalVO from(RagRetrievalBO result) {
        var q=result.query();
        return new RagRetrievalVO(result.status(),new QueryVO(q.originalQuery(),q.effectiveQuery(),q.subQueries(),q.kbIds(),
                q.needsClarification(),q.clarificationQuestion(),q.degraded(),q.fallbackReason(),q.promptVersion(),q.model(),q.termVersion(),q.promptHash()),
                result.retrieval()==null?null:SearchWebConverter.toRetrievalVO(result.retrieval()));
    }
}
