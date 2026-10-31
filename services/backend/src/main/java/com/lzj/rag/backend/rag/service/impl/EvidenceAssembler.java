package com.lzj.rag.backend.rag.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lzj.rag.backend.knowledgebase.exception.KnowledgeBaseException;
import com.lzj.rag.backend.model.catalog.EmbeddingCatalog;
import com.lzj.rag.backend.model.service.TokenCounterClient;
import com.lzj.rag.backend.rag.config.ContextProperties;
import com.lzj.rag.backend.rag.service.bo.*;
import com.lzj.rag.backend.search.budget.RetrievalDeadline;
import com.lzj.rag.backend.search.repository.EvidenceRepository;
import com.lzj.rag.backend.search.service.bo.*;
import com.lzj.rag.backend.security.model.KnowledgeBasePrincipal;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

@Service
@EnableConfigurationProperties(ContextProperties.class)
public class EvidenceAssembler {
    public static final String VERSION="evidence-context-v1-utf8-guard";
    private final EvidenceRepository records;
    private final TokenCounterClient tokens;
    private final ContextProperties config;
    private final ObjectMapper json;
    public EvidenceAssembler(EvidenceRepository records,TokenCounterClient tokens,ContextProperties config,ObjectMapper json) {
        this.records=records;this.tokens=tokens;this.config=config;this.json=json;
    }
    public EvidenceContextBO assemble(KnowledgeBasePrincipal actor,SearchBO scope,QueryPlanBO query,List<EvidenceBO> candidates,
                                      List<Map<String,String>> history,String requestId,Instant deadline) {
        RetrievalDeadline.check(deadline);
        var live=records.hydrate(actor,scope,candidates.stream().map(EvidenceBO::chunkId).toList());
        var ordered=new ArrayList<EvidenceBO>();
        var seen=new HashSet<UUID>();
        for(var candidate:candidates) {
            var current=live.get(candidate.chunkId());
            if(current!=null && current.generation()==candidate.generation() && current.content().equals(candidate.content()) && seen.add(current.chunkId()))
                ordered.add(new EvidenceBO(current.chunkId(),current.kbId(),current.documentId(),current.documentName(),current.chunkIndex(),current.generation(),
                        current.content(),candidate.score(),candidate.scoreType(),current.sourceMetadata(),current.fileUrl(),candidate.fusionScore(),candidate.rerankScore(),candidate.channelScores()));
        }
        var neighbors=config.neighborRadius()==0?List.<EvidenceBO>of():records.adjacent(actor,scope,ordered.stream().limit(config.topK()).map(EvidenceBO::chunkId).toList(),config.neighborRadius());
        var selected=new ArrayList<EvidenceSnapshotBO>();
        var documents=new HashMap<UUID,Integer>();var sources=new HashMap<String,Integer>();var queries=new HashMap<String,Integer>();
        boolean truncated=false;
        // 先给各子问题一个机会，再按引擎顺序补充，防止单篇文档独占上下文。
        var priority=new LinkedHashSet<EvidenceBO>();
        for(String sub:query.subQueries()) ordered.stream().filter(e->belongs(e,sub)).findFirst().ifPresent(priority::add);
        priority.addAll(ordered);
        int max=Math.min(scope.topK(),config.topK());
        int directMax=config.neighborRadius()>0?Math.max(1,max-1):max;
        for(var e:priority) {
            RetrievalDeadline.check(deadline);
            if(selected.size()>=directMax) {truncated=true;break;}
            var subs=query.subQueries().stream().filter(s->belongs(e,s)).toList();
            if(subs.isEmpty()) subs=List.of(query.effectiveQuery());
            String source=sourceKey(e);
            if(documents.getOrDefault(e.documentId(),0)>=config.perDocument() || sources.getOrDefault(source,0)>=config.perSource()
                    || subs.stream().allMatch(s->queries.getOrDefault(s,0)>=config.perQuery())) {truncated=true;continue;}
            var snapshot=fit(e,selected,query.originalQuery(),history,false,null,subs,deadline);
            if(snapshot==null){truncated=true;continue;}
            selected.add(snapshot);truncated|=snapshot.truncated();
            documents.merge(e.documentId(),1,Integer::sum);sources.merge(source,1,Integer::sum);
            subs.forEach(s->queries.merge(s,1,Integer::sum));
        }
        for(var neighbor:neighbors) {
            if(selected.size()>=max) {truncated=true;break;}
            if(selected.stream().anyMatch(e->e.chunkId().equals(neighbor.chunkId())) || documents.getOrDefault(neighbor.documentId(),0)>=config.perDocument()) continue;
            var parent=selected.stream().filter(e->!e.adjacent() && e.documentId().equals(neighbor.documentId()) && e.generation()==neighbor.generation()
                    && Math.abs(e.chunkIndex()-neighbor.chunkIndex())==1).findFirst();
            if(parent.isEmpty()) continue;
            String source=sourceKey(neighbor);
            if(sources.getOrDefault(source,0)>=config.perSource() || parent.get().subQueries().stream().allMatch(s->queries.getOrDefault(s,0)>=config.perQuery())) continue;
            var snapshot=fit(neighbor,selected,query.originalQuery(),history,true,parent.get().chunkId(),parent.get().subQueries(),deadline);
            if(snapshot!=null){selected.add(snapshot);documents.merge(neighbor.documentId(),1,Integer::sum);sources.merge(source,1,Integer::sum);
                parent.get().subQueries().forEach(s->queries.merge(s,1,Integer::sum));truncated|=snapshot.truncated();}
        }
        if(!candidates.isEmpty() && selected.isEmpty())
            throw new KnowledgeBaseException(ordered.isEmpty()?409:422,ordered.isEmpty()?"RAG_EVIDENCE_CHANGED":"RAG_CONTEXT_BUDGET",
                    "No usable evidence remains within current permissions and context limits");
        revalidate(actor,scope,selected,deadline);
        String modelContext=modelJson(selected);
        long estimated=0;
        if(!selected.isEmpty()) {
            String envelope=envelope(query.originalQuery(),history,selected);
            var estimate=tokens.estimate(envelope,requestId,deadline);
            estimated=estimate.tokens();
            if(Math.ceil(estimated*1.25)>config.inputBudget())
                throw new KnowledgeBaseException(422,"RAG_CONTEXT_BUDGET","Selected context exceeds the reserved input budget");
        }
        RetrievalDeadline.check(deadline);
        revalidate(actor,scope,selected,deadline);
        return new EvidenceContextBO(List.copyOf(selected),modelContext,estimated,config.inputBudget(),true,truncated,VERSION,EmbeddingCatalog.sha256(modelContext));
    }

    /** 模型调用前再次检查快照，版本/正文变化要求调用者重新检索，不发送旧证据。 */
    public void revalidate(KnowledgeBasePrincipal actor,SearchBO scope,List<EvidenceSnapshotBO> snapshots,Instant deadline) {
        RetrievalDeadline.check(deadline);
        var current=records.hydrate(actor,scope,snapshots.stream().map(EvidenceSnapshotBO::chunkId).toList());
        for(var snapshot:snapshots) {
            var live=current.get(snapshot.chunkId());
            if(live==null || !live.kbId().equals(snapshot.kbId()) || !live.documentId().equals(snapshot.documentId())
                    || live.generation()!=snapshot.generation() || !EmbeddingCatalog.sha256(live.content()).equals(snapshot.sourceContentHash()))
                throw new KnowledgeBaseException(409,"RAG_EVIDENCE_CHANGED","Evidence changed or became unavailable; retrieve again");
        }
        RetrievalDeadline.check(deadline);
    }

    private EvidenceSnapshotBO fit(EvidenceBO e,List<EvidenceSnapshotBO> selected,String question,List<Map<String,String>> history,
                                   boolean adjacent,UUID parent,List<String> subs,Instant deadline) {
        String text=e.content();String original=text;
        while(!text.isBlank()) {
            RetrievalDeadline.check(deadline);
            var snapshot=new EvidenceSnapshotBO("E"+(selected.size()+1),e.kbId(),e.documentId(),e.chunkId(),e.generation(),e.chunkIndex(),e.documentName(),
                    text,e.sourceMetadata(),e.fileUrl(),EmbeddingCatalog.sha256(original),EmbeddingCatalog.sha256(text),!text.equals(original),adjacent,parent,subs,
                    adjacent?null:e.fusionScore(),adjacent?null:e.rerankScore());
            var trial=new ArrayList<>(selected);trial.add(snapshot);
            // 估算器非模型 tokenizer；额外用 UTF-8 字节总额限制资料、来源和问题/历史。
            if(envelope(question,history,trial).getBytes(StandardCharsets.UTF_8).length<=config.inputBudget()) return snapshot;
            String[] lines=text.split("\n",-1);
            if(text.stripLeading().startsWith("|")) {
                if(lines.length<=3) return null;
                text=String.join("\n",Arrays.copyOf(lines,Math.max(3,lines.length*3/4)));
            } else {
                int cps=text.codePointCount(0,text.length());
                if(cps<=64) return null;
                int end=text.offsetByCodePoints(0,Math.max(32,cps*3/4));
                int boundary=text.lastIndexOf('\n',end);
                text=text.substring(0,boundary>0?boundary:end).stripTrailing();
            }
        }
        return null;
    }
    private boolean belongs(EvidenceBO e,String sub) { return e.channelScores().stream().anyMatch(c->sub.equals(c.subQuery())) || e.channelScores().stream().allMatch(c->c.subQuery()==null); }
    private String sourceKey(EvidenceBO e) { return e.documentId()+":"+Objects.toString(e.sourceMetadata().get("chapterPath"),Objects.toString(e.sourceMetadata().get("sheetName"),"document")); }
    private String envelope(String question,List<Map<String,String>> history,List<EvidenceSnapshotBO> sources) {
        var value=new LinkedHashMap<String,Object>();value.put("question",question);value.put("history",history);value.put("evidence",modelRows(sources));return encode(value);
    }
    public String modelJson(List<EvidenceSnapshotBO> sources) { return encode(modelRows(sources)); }
    private List<Map<String,Object>> modelRows(List<EvidenceSnapshotBO> sources) {
        return sources.stream().map(e->{Map<String,Object> row=new LinkedHashMap<>();row.put("evidenceId",e.evidenceId());row.put("document",e.documentName());
            row.put("content",e.content());row.put("source",e.sourceMetadata());row.put("adjacent",e.adjacent());row.put("truncated",e.truncated());return row;}).toList();
    }
    private String encode(Object value) {try{return json.writeValueAsString(value);}catch(java.io.IOException e){throw new IllegalStateException("Cannot serialize evidence",e);}}
}
