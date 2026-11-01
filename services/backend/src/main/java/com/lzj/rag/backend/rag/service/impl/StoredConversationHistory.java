package com.lzj.rag.backend.rag.service.impl;

import com.lzj.rag.backend.knowledgebase.exception.KnowledgeBaseException;
import com.lzj.rag.backend.rag.repository.AnswerLedger;
import com.lzj.rag.backend.rag.service.ConversationHistory;
import com.lzj.rag.backend.search.service.bo.SearchBO;
import com.lzj.rag.backend.security.model.KnowledgeBasePrincipal;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.*;

@Component
public class StoredConversationHistory implements ConversationHistory {
    private final AnswerLedger ledger;private final EvidenceAssembler sources;
    public StoredConversationHistory(AnswerLedger ledger,EvidenceAssembler sources){this.ledger=ledger;this.sources=sources;}
    public List<Map<String,String>> recent(KnowledgeBasePrincipal actor,UUID id,Instant deadline) {
        com.lzj.rag.backend.search.budget.RetrievalDeadline.check(deadline);
        var turns=new ArrayList<>(ledger.recent(actor,id,6));Collections.reverse(turns);
        var history=new ArrayList<Map<String,String>>();int chars=0;
        for(var turn:turns) {
            if(turn.status().equals("RUNNING"))continue;
            if(chars+turn.question().length()>3000)continue;
            history.add(Map.of("role","user","content",turn.question()));chars+=turn.question().length();
            var answer=turn.result();
            if(answer==null || !Set.of("COMPLETED","NEEDS_CLARIFICATION","NO_EVIDENCE").contains(answer.status()) || chars+answer.answer().length()>3000)continue;
            try {
                var kbs=answer.sources().stream().map(e->e.kbId()).distinct().toList();
                if(!answer.sources().isEmpty())sources.revalidate(actor,new SearchBO(turn.question(),kbs,null,5,null),answer.sources(),deadline);
                history.add(Map.of("role","assistant","content",answer.answer()));chars+=answer.answer().length();
            } catch(KnowledgeBaseException revoked) {
                if(!Set.of(404,409).contains(revoked.status())) throw revoked;
            }
        }
        com.lzj.rag.backend.search.budget.RetrievalDeadline.check(deadline);return List.copyOf(history);
    }
}
