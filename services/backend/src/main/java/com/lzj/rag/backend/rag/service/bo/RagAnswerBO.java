package com.lzj.rag.backend.rag.service.bo;

import com.fasterxml.jackson.databind.JsonNode;
import com.lzj.rag.backend.search.service.bo.RetrievalTraceBO;
import java.util.*;

public record RagAnswerBO(UUID answerId,UUID conversationId,String requestId,String status,String answer,
        List<CitationBO> citations,List<EvidenceSnapshotBO> sources,boolean degraded,String citationValidation,
        JsonNode usage,List<ModelCallBO> modelCalls,QueryPlanBO query,RetrievalTraceBO retrievalTrace,
        String evidenceContextHash,boolean repairAttempted,String errorCode) {
    public record CitationBO(String evidenceId,UUID documentId,UUID chunkId,long generation,String snapshotHash) {}
    public record ModelCallBO(String purpose,String model,String promptVersion,String promptHash,JsonNode usage) {}
}
