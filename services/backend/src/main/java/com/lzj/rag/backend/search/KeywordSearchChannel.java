package com.lzj.rag.backend.search;
import com.lzj.rag.backend.model.EmbeddingCatalog;
import com.lzj.rag.backend.security.KnowledgeBasePrincipal;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.*;

@Component
public class KeywordSearchChannel implements SearchChannel {
    private final KeywordIndex index;private final EvidenceRepository records;
    public KeywordSearchChannel(KeywordIndex index,EvidenceRepository records){this.index=index;this.records=records;}
    public String name(){return "KEYWORD";}
    public Result search(KnowledgeBasePrincipal actor,KnowledgeSearchTypes.Request request,String id,int limit,Instant deadline) {
        var result=new LinkedHashMap<UUID,Candidate>();boolean capped=false;
        for(int round=0;round<2 && Instant.now().isBefore(deadline);round++) {
            var keys=records.versionKeys(actor,request);var hits=index.query(request.query().strip(),actor.tenantId(),actor.userId(),keys,Math.min(200,limit*2),deadline);
            var ids=hits.stream().map(KeywordIndex.Hit::chunkId).toList();var live=records.hydrate(actor,request,ids);var revisions=records.validKeywordRevisions(actor,request,ids);
            for(var hit:hits) {
                var row=live.get(hit.chunkId());if(row==null || !Objects.equals(hit.syncRevision(),revisions.get(hit.chunkId())) || !EmbeddingCatalog.sha256(row.content()).equals(hit.contentHash()))continue;
                result.putIfAbsent(hit.chunkId(),new Candidate(row,name(),hit.rank(),hit.score(),"BM25",hit.syncRevision(),hit.contentHash()));
            }
            if(result.size()>=limit || hits.size()<Math.min(200,limit*2)){capped=false;break;}capped=true;
        }
        return new Result(result.values().stream().sorted(Comparator.comparingDouble(Candidate::rawScore).reversed().thenComparing(c->c.evidence().chunkId())).limit(limit).toList(),capped);
    }
}
