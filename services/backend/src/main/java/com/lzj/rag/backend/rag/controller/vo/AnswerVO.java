package com.lzj.rag.backend.rag.controller.vo;

import com.fasterxml.jackson.databind.JsonNode;
import com.lzj.rag.backend.rag.service.bo.*;
import com.lzj.rag.backend.search.controller.vo.RetrievalTraceVO;
import com.lzj.rag.backend.search.converter.SearchWebConverter;
import java.util.*;

public record AnswerVO(UUID answerId,UUID conversationId,String requestId,String status,String answer,
        List<CitationVO> citations,List<EvidenceContextVO.SnapshotVO> sources,List<String> unavailableSources,
        boolean degraded,String citationValidation,JsonNode usage,List<ModelCallVO> modelCalls,
        RagRetrievalVO.QueryVO query,RetrievalTraceVO retrievalTrace,String evidenceContextHash,boolean repairAttempted,String errorCode) {
    public record CitationVO(String evidenceId,UUID documentId,UUID chunkId,long generation,String snapshotHash) {}
    public record ModelCallVO(String purpose,String model,String promptVersion,String promptHash,JsonNode usage) {}
    public static AnswerVO from(RagAnswerBO a) {
        if(a==null)return null;var ids=a.sources().stream().map(e->e.evidenceId()).toList();var q=a.query();
        return new AnswerVO(a.answerId(),a.conversationId(),a.requestId(),a.status(),a.answer(),a.citations().stream().map(c->new CitationVO(c.evidenceId(),c.documentId(),c.chunkId(),c.generation(),c.snapshotHash())).toList(),
                a.sources().stream().map(EvidenceContextVO.SnapshotVO::from).toList(),a.citations().stream().map(c->c.evidenceId()).filter(id->!ids.contains(id)).toList(),a.degraded(),a.citationValidation(),a.usage(),
                a.modelCalls().stream().map(c->new ModelCallVO(c.purpose(),c.model(),c.promptVersion(),c.promptHash(),c.usage())).toList(),
                q==null?null:new RagRetrievalVO.QueryVO(q.originalQuery(),q.effectiveQuery(),q.subQueries(),q.kbIds(),q.needsClarification(),q.clarificationQuestion(),q.degraded(),q.fallbackReason(),q.promptVersion(),q.model(),q.termVersion(),q.promptHash()),
                a.retrievalTrace()==null?null:SearchWebConverter.toRetrievalTraceVO(a.retrievalTrace()),a.evidenceContextHash(),a.repairAttempted(),a.errorCode());
    }
}
