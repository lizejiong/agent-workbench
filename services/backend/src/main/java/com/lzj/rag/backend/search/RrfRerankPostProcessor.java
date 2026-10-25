package com.lzj.rag.backend.search;
import com.lzj.rag.backend.model.*;
import com.lzj.rag.backend.security.KnowledgeBasePrincipal;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.*;
import static com.lzj.rag.backend.search.KnowledgeSearchTypes.*;

@Component
public class RrfRerankPostProcessor implements SearchResultPostProcessor {
    private final HybridProperties config;private final RerankClient reranker;private final EvidenceRepository records;
    private static class Fused {Evidence evidence;final Map<String,SearchChannel.Candidate> channels=new TreeMap<>();double fusion;Double rerank;Fused(Evidence evidence){this.evidence=evidence;}}
    public RrfRerankPostProcessor(HybridProperties config,RerankClient reranker,EvidenceRepository records){this.config=config;this.reranker=reranker;this.records=records;}
    private void score(Fused fused){fused.fusion=fused.channels.values().stream().mapToDouble(c->(c.channel().equals("VECTOR")?config.vectorWeight():config.keywordWeight())/(config.rrfConstant()+c.rank())).sum();}
    public Result process(KnowledgeBasePrincipal actor,Request request,List<SearchChannel.Candidate> candidates,String requestId,boolean rerank,int fusionLimit) {
        var byId=new LinkedHashMap<UUID,Fused>();
        for(var candidate:candidates){var fused=byId.computeIfAbsent(candidate.evidence().chunkId(),k->new Fused(candidate.evidence()));fused.channels.merge(candidate.channel(),candidate,(a,b)->a.rank()<=b.rank()?a:b);}
        var order=Comparator.<Fused>comparingDouble(f->f.fusion).reversed().thenComparing(f->f.evidence.chunkId());
        byId.values().forEach(this::score);var fused=new ArrayList<>(byId.values().stream().sorted(order).limit(fusionLimit).toList());
        // Never send stale or unauthorized index text to an external reranker.
        filter(actor,request,fused);fused.sort(order);String status=rerank?"SKIPPED_EMPTY":"DISABLED";
        if(rerank && !fused.isEmpty()) {
            try {
                var ranking=reranker.rerank(request.query().strip(),fused.stream().map(f->new RerankClient.Input(f.evidence.chunkId(),f.evidence.content())).toList(),requestId,Instant.now().plus(config.rerank().timeout()));
                if(ranking.size()!=fused.size() || ranking.stream().map(RerankClient.Ranked::index).distinct().count()!=fused.size())throw new IllegalStateException();
                for(var ranked:ranking){if(ranked.index()<0 || ranked.index()>=fused.size() || !Double.isFinite(ranked.score()))throw new IllegalStateException();fused.get(ranked.index()).rerank=ranked.score();}
                status="APPLIED";
            }catch(RuntimeException error) {
                if(Thread.currentThread().isInterrupted())throw error;
                fused.forEach(f->f.rerank=null);status=request.minRerankScore()==null?"FALLBACK_FUSION":"THRESHOLD_UNAVAILABLE";
            }
        }
        filter(actor,request,fused); // Names/coordinates are refreshed; model waiting cannot leak disabled/edited/deleted bodies.
        Comparator<Fused> resultOrder="APPLIED".equals(status)?Comparator.<Fused>comparingDouble(f->f.rerank).reversed().thenComparing(order):order;
        if(request.minRerankScore()!=null)fused.removeIf(f->f.rerank==null || f.rerank<request.minRerankScore());
        var result=fused.stream().sorted(resultOrder).limit(request.topK()).map(f->{var e=f.evidence;return new Evidence(e.chunkId(),e.kbId(),e.documentId(),e.documentName(),e.chunkIndex(),e.generation(),e.content(),f.fusion,"RRF",e.sourceMetadata(),e.fileUrl(),f.fusion,f.rerank,f.channels.values().stream().map(c->new ChannelScore(c.channel(),c.rank(),c.rawScore(),c.scoreType())).toList());}).toList();
        return new Result(result,status);
    }
    private void filter(KnowledgeBasePrincipal actor,Request request,List<Fused> fused) {
        var ids=fused.stream().map(f->f.evidence.chunkId()).toList();var live=records.hydrate(actor,request,ids);var revisions=records.validKeywordRevisions(actor,request,ids);
        fused.removeIf(f->{var current=live.get(f.evidence.chunkId());if(current==null)return true;
            f.channels.entrySet().removeIf(entry->{var candidate=entry.getValue();return candidate.evidence().generation()!=current.generation()
                    || !candidate.evidence().content().equals(current.content()) || entry.getKey().equals("KEYWORD") && !Objects.equals(candidate.syncRevision(),revisions.get(current.chunkId()));});
            f.evidence=current;score(f);return f.channels.isEmpty();});
    }
}
