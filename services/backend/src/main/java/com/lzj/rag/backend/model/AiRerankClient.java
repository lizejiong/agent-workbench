package com.lzj.rag.backend.model;
import com.lzj.rag.backend.knowledgebase.KnowledgeBaseException;
import com.lzj.rag.backend.search.HybridProperties;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.*;

/** Existing AI service owns Bailian input/parameters and SiliconFlow protocol differences. */
@Component
public class AiRerankClient implements RerankClient {
    private final AiEmbeddingClient transport;private final HybridProperties config;
    public AiRerankClient(AiEmbeddingClient transport,HybridProperties config){this.transport=transport;this.config=config;}
    @Override public List<Ranked> rerank(String query,List<Input> candidates,String requestId,Instant deadline) {
        long chars=query.length()+candidates.stream().mapToLong(c->c.content().length()).sum();
        if(candidates.isEmpty() || chars>config.rerank().maxInputChars())throw invalid("KB_RERANK_BUDGET");
        var result=transport.rerankCall(Map.of("query",query,"documents",candidates.stream().map(c->Map.of("id",c.chunkId().toString(),"text",c.content())).toList(),"topN",candidates.size(),"model",config.rerank().model()),requestId,deadline);
        if(!config.rerank().model().equals(result.path("model").asText()) || !result.path("results").isArray() || result.path("results").size()!=candidates.size())throw invalid("KB_RERANK_INVALID_RESPONSE");
        var scores=new ArrayList<Ranked>();var seen=new HashSet<Integer>();
        for(var row:result.path("results")) {
            var index=row.path("index");var score=row.path("score");
            if(!index.isIntegralNumber() || !index.canConvertToInt() || index.intValue()<0 || index.intValue()>=candidates.size() || !seen.add(index.intValue())
                    || !score.isNumber() || !Double.isFinite(score.doubleValue()) || !candidates.get(index.intValue()).chunkId().toString().equals(row.path("chunkId").asText()))throw invalid("KB_RERANK_INVALID_RESPONSE");
            scores.add(new Ranked(index.intValue(),score.doubleValue()));
        }
        return scores.stream().sorted(Comparator.comparingDouble(Ranked::score).reversed().thenComparingInt(Ranked::index)).toList();
    }
    private static KnowledgeBaseException invalid(String code){return new KnowledgeBaseException(502,code,"Rerank output or input budget does not match the fixed candidate set");}
}
