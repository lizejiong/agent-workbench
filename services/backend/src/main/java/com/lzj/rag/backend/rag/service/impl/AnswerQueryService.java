package com.lzj.rag.backend.rag.service.impl;

import com.lzj.rag.backend.knowledgebase.exception.KnowledgeBaseException;
import com.lzj.rag.backend.rag.repository.AnswerLedger;
import com.lzj.rag.backend.rag.service.bo.*;
import com.lzj.rag.backend.search.service.bo.SearchBO;
import com.lzj.rag.backend.security.model.KnowledgeBasePrincipal;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;

/** 历史答案归对话所有者；失效来源只保留引用标识/哈希，不重新下发旧证据正文。 */
@Service
public class AnswerQueryService {
    private final AnswerLedger ledger;private final EvidenceAssembler evidence;
    public AnswerQueryService(AnswerLedger ledger,EvidenceAssembler evidence){this.ledger=ledger;this.evidence=evidence;}
    public AnswerRunBO get(KnowledgeBasePrincipal actor,UUID id){return visible(actor,ledger.get(actor,id));}
    public List<AnswerRunBO> history(KnowledgeBasePrincipal actor,UUID conversation){return ledger.recent(actor,conversation,20).stream().map(r->visible(actor,r)).toList();}
    private AnswerRunBO visible(KnowledgeBasePrincipal actor,AnswerRunBO run) {
        var a=run.result();if(a==null)return run;
        var sources=new ArrayList<EvidenceSnapshotBO>();
        for(var s:a.sources()) try {
            evidence.revalidate(actor,new SearchBO(run.question(),List.of(s.kbId()),null,5,null),List.of(s),Instant.now().plusSeconds(2));sources.add(s);
        } catch(KnowledgeBaseException revoked){if(revoked.status()!=404 && revoked.status()!=409)throw revoked;}
        var filtered=new RagAnswerBO(a.answerId(),a.conversationId(),a.requestId(),a.status(),a.answer(),a.citations(),List.copyOf(sources),a.degraded(),a.citationValidation(),a.usage(),a.modelCalls(),a.query(),a.retrievalTrace(),a.evidenceContextHash(),a.repairAttempted(),a.errorCode());
        return new AnswerRunBO(run.id(),run.conversationId(),run.requestId(),run.question(),run.status(),run.deadline(),run.answerText(),filtered,run.errorCode(),run.createdAt(),run.updatedAt());
    }
}
