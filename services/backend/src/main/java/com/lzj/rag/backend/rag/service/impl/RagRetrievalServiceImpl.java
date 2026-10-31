package com.lzj.rag.backend.rag.service.impl;

import com.lzj.rag.backend.rag.service.*;
import com.lzj.rag.backend.rag.service.bo.*;
import com.lzj.rag.backend.search.budget.RetrievalDeadline;
import com.lzj.rag.backend.search.service.HybridRetrievalService;
import com.lzj.rag.backend.search.service.bo.*;
import com.lzj.rag.backend.security.model.KnowledgeBasePrincipal;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;

@Service
public class RagRetrievalServiceImpl implements RagRetrievalService {
    private final HybridRetrievalService engine;
    private final QueryUnderstandingService understanding;
    private final ConversationHistory history;
    private final EvidenceAssembler assembler;
    public RagRetrievalServiceImpl(HybridRetrievalService engine, QueryUnderstandingService understanding, ConversationHistory history,EvidenceAssembler assembler) {
        this.engine=engine;this.understanding=understanding;this.history=history;
        this.assembler=assembler;
    }
    public RagRetrievalBO retrieve(KnowledgeBasePrincipal actor,RagRetrieveBO request,String requestId,QueryMode mode,Instant deadline) {
        var scope=engine.prepare(actor,request.search(),deadline);
        var messages=mode==QueryMode.USER ? history.recent(actor,request.conversationId()) : List.<Map<String,String>>of();
        var plan=understanding.understand(scope,messages,requestId,deadline);
        RetrievalDeadline.check(deadline);
        if(plan.needsClarification()) return new RagRetrievalBO("NEEDS_CLARIFICATION",plan,null);
        var search=new SearchBO(plan.effectiveQuery(),plan.kbIds(),scope.documentIds(),scope.topK(),scope.minScore(),scope.strategy(),scope.rerank(),scope.minRerankScore());
        var result=engine.retrieveQueries(actor,search,plan.subQueries(),requestId,deadline);
        var context=assembler.assemble(actor,search,plan,result.evidence(),messages,requestId,deadline);
        var byId=new HashMap<UUID,EvidenceBO>();result.evidence().forEach(e->byId.put(e.chunkId(),e));
        var safe=context.evidence().stream().map(e->{var scored=e.adjacent()?null:byId.get(e.chunkId());
            return new EvidenceBO(e.chunkId(),e.kbId(),e.documentId(),e.documentName(),e.chunkIndex(),e.generation(),e.content(),
                    scored==null?0:scored.score(),scored==null?"UNSCORED":scored.scoreType(),e.sourceMetadata(),e.fileUrl(),
                    e.fusionScore(),e.rerankScore(),scored==null?List.of():scored.channelScores());}).toList();
        var filtered=new RetrievalBO(safe,result.requestedStrategy(),result.strategy(),result.rerankStatus(),result.channels(),result.channelCandidates(),
                result.fusionCandidates(),result.topK(),result.refillLimitReached(),result.minRerankScore(),result.status(),result.outcome(),result.trace(),result.candidateBudgetTruncated());
        return new RagRetrievalBO(result.outcome().equals("EVIDENCE")?"READY":result.outcome().equals("NO_EVIDENCE")?"NO_EVIDENCE":"UNAVAILABLE",plan,filtered,context);
    }
}
