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
    public RagRetrievalServiceImpl(HybridRetrievalService engine, QueryUnderstandingService understanding, ConversationHistory history) {
        this.engine=engine;this.understanding=understanding;this.history=history;
    }
    public RagRetrievalBO retrieve(KnowledgeBasePrincipal actor,RagRetrieveBO request,String requestId,QueryMode mode,Instant deadline) {
        var scope=engine.prepare(actor,request.search(),deadline);
        var messages=mode==QueryMode.USER ? history.recent(actor,request.conversationId()) : List.<Map<String,String>>of();
        var plan=understanding.understand(scope,messages,requestId,deadline);
        RetrievalDeadline.check(deadline);
        if(plan.needsClarification()) return new RagRetrievalBO("NEEDS_CLARIFICATION",plan,null);
        var search=new SearchBO(plan.effectiveQuery(),plan.kbIds(),scope.documentIds(),scope.topK(),scope.minScore(),scope.strategy(),scope.rerank(),scope.minRerankScore());
        var result=engine.retrieveQueries(actor,search,plan.subQueries(),requestId,deadline);
        return new RagRetrievalBO(result.outcome().equals("EVIDENCE")?"READY":result.outcome().equals("NO_EVIDENCE")?"NO_EVIDENCE":"UNAVAILABLE",plan,result);
    }
}
