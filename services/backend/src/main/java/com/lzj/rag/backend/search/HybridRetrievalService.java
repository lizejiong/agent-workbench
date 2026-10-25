package com.lzj.rag.backend.search;
import com.lzj.rag.backend.document.DocumentService;
import com.lzj.rag.backend.knowledgebase.*;
import com.lzj.rag.backend.security.KnowledgeBasePrincipal;
import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static com.lzj.rag.backend.search.KnowledgeSearchTypes.*;

@Service
public class HybridRetrievalService {
    private final KnowledgeSearchService vector;private final SearchProperties limits;private final HybridProperties config;
    private final KeywordProperties keyword;private final KnowledgeBaseService bases;private final DocumentService documents;
    private final SearchResultPostProcessor post;private final Map<String,SearchChannel> channels=new HashMap<>();
    private final ThreadPoolTaskExecutor executor=new ThreadPoolTaskExecutor();
    private record Completed(SearchChannel.Result result,long finished) { }
    private record Running(String name,long deadline,Future<Completed> future) { }
    public HybridRetrievalService(KnowledgeSearchService vector,SearchProperties limits,HybridProperties config,KeywordProperties keyword,
            KnowledgeBaseService bases,DocumentService documents,SearchResultPostProcessor post,List<SearchChannel> channels) {
        this.vector=vector;this.limits=limits;this.config=config;this.keyword=keyword;this.bases=bases;this.documents=documents;this.post=post;
        for(var channel:channels)if(this.channels.putIfAbsent(channel.name(),channel)!=null)throw new IllegalArgumentException("Duplicate search channel");
        executor.setCorePoolSize(config.concurrency());executor.setMaxPoolSize(config.concurrency());executor.setQueueCapacity(0);executor.setThreadNamePrefix("kb-retrieval-");executor.setWaitForTasksToCompleteOnShutdown(false);executor.initialize();
    }
    public Retrieval retrieve(KnowledgeBasePrincipal actor,Request request,String requestId) {
        if(request.minRerankScore()==null && config.rerank().minScore()!=null)request=new Request(request.query(),request.kbIds(),request.documentIds(),request.topK(),request.minScore(),request.strategy(),request.rerank(),config.rerank().minScore());
        authorize(actor,request);
        String wanted=request.strategy()==null?config.strategy():request.strategy();String strategy=wanted.equals("AUTO")?(keyword.enabled()?"HYBRID":"VECTOR"):wanted;
        if(!keyword.enabled() && !strategy.equals("VECTOR"))throw invalid("KB_KEYWORD_DISABLED","Enable keyword service or select VECTOR");
        boolean rerank=request.rerank()==null?config.rerank().enabled():request.rerank();
        if(rerank && !config.rerank().enabled())throw invalid("KB_RERANK_DISABLED","Rerank is not configured");
        if(request.minRerankScore()!=null && (!rerank || !Double.isFinite(request.minRerankScore())))throw invalid("KB_RERANK_REQUIRED","A finite rerank threshold requires enabled reranking");
        if(request.minScore()!=null && !strategy.equals("VECTOR"))throw invalid("KB_AMBIGUOUS_THRESHOLD","minScore is a cosine threshold for VECTOR only; use minRerankScore with reranking for hybrid evidence");
        // Keep the existing vector-only contract, scores and failure codes when optional services are off.
        if(strategy.equals("VECTOR") && !rerank) {
            var rows=vector.search(actor,request,requestId);var result=new ArrayList<Evidence>();int rank=0;
            for(var e:rows)result.add(new Evidence(e.chunkId(),e.kbId(),e.documentId(),e.documentName(),e.chunkIndex(),e.generation(),e.content(),e.score(),e.scoreType(),e.sourceMetadata(),e.fileUrl(),null,null,List.of(new ChannelScore("VECTOR",++rank,e.score(),e.scoreType()))));
            return new Retrieval(result,wanted,"VECTOR","DISABLED",List.of(new ChannelState("VECTOR","SUCCEEDED",rows.size(),null)),request.topK(),request.topK(),request.topK(),false,request.minRerankScore());
        }
        int recall=Math.max(config.channelCandidates(),request.topK()),fusion=Math.max(config.fusionCandidates(),request.topK());
        final Request effectiveRequest=request;
        var tasks=new ArrayList<Running>();var states=new ArrayList<ChannelState>();var candidates=new ArrayList<SearchChannel.Candidate>();boolean capped=false;
        for(String name:strategy.equals("HYBRID")?List.of("VECTOR","KEYWORD"):List.of(strategy)) {
            Duration timeout=name.equals("VECTOR")?config.vectorTimeout():config.keywordTimeout();long end=System.nanoTime()+timeout.toNanos();Instant deadline=Instant.now().plus(timeout);
            try{tasks.add(new Running(name,end,executor.submit(()->new Completed(channels.get(name).search(actor,effectiveRequest,requestId,recall,deadline),System.nanoTime()))));}
            catch(RuntimeException busy){states.add(new ChannelState(name,"FAILED",0,"KB_SEARCH_CAPACITY"));}
        }
        try {
            for(var task:tasks) {
                try {
                    Completed completed=task.future().get(Math.max(1,task.deadline()-System.nanoTime()),TimeUnit.NANOSECONDS);
                    if(completed.finished()>task.deadline())throw new TimeoutException();
                    candidates.addAll(completed.result().candidates());capped|=completed.result().refillLimitReached();states.add(new ChannelState(task.name(),"SUCCEEDED",completed.result().candidates().size(),null));
                }catch(TimeoutException e){task.future().cancel(true);states.add(new ChannelState(task.name(),"TIMEOUT",0,"KB_SEARCH_CHANNEL_TIMEOUT"));}
                catch(ExecutionException e){states.add(new ChannelState(task.name(),"FAILED",0,e.getCause() instanceof KnowledgeBaseException kb?kb.code():"KB_SEARCH_CHANNEL_FAILED"));}
            }
        }catch(InterruptedException e){tasks.forEach(t->t.future().cancel(true));Thread.currentThread().interrupt();throw new KnowledgeBaseException(503,"KB_SEARCH_CANCELLED","Retrieval was cancelled");}
        long succeeded=states.stream().filter(s->s.status().equals("SUCCEEDED")).count();
        if(succeeded==0)throw new KnowledgeBaseException(503,"KB_SEARCH_UNAVAILABLE","All requested search channels failed");
        var processed=post.process(actor,request,candidates,requestId,rerank,fusion);
        String used=states.stream().filter(s->s.status().equals("SUCCEEDED")).map(ChannelState::channel).sorted().reduce((a,b)->a+"+"+b).orElseThrow();
        return new Retrieval(processed.evidence(),wanted,used,processed.rerankStatus(),states,recall,fusion,request.topK(),capped,request.minRerankScore());
    }
    private void authorize(KnowledgeBasePrincipal actor,Request request) {
        String query=request.query()==null?"":request.query().strip();
        if(query.isBlank() || query.codePointCount(0,query.length())>limits.maxQueryChars() || query.codePoints().anyMatch(c->c==0 || c>=0xd800 && c<=0xdfff)
                || request.topK()==null || request.topK()<1 || request.topK()>limits.maxTopK() || !request.isMinScoreValid())throw invalid("KB_INVALID_INPUT","Check query, topK and score limits");
        checkIds(request.kbIds(),limits.maxKbIds(),false);checkIds(request.documentIds(),limits.maxDocumentIds(),true);
        for(UUID kb:request.kbIds())bases.requireOwned(actor,kb);
        if(request.documentIds()!=null)for(UUID doc:request.documentIds())if(!request.kbIds().contains(documents.requireOwned(actor,doc).getKbId()))throw invalid("KB_SEARCH_DOCUMENT_SCOPE","Every document must belong to a selected knowledge base");
    }
    private static void checkIds(List<UUID> ids,int max,boolean optional){if(ids==null && optional)return;if(ids==null || ids.isEmpty() || ids.size()>max || ids.stream().anyMatch(Objects::isNull) || new HashSet<>(ids).size()!=ids.size())throw invalid("KB_INVALID_INPUT","Resource ids must be nonempty, unique and within configured limits");}
    private static KnowledgeBaseException invalid(String code,String message){return new KnowledgeBaseException(400,code,message);}
    @PreDestroy void close(){executor.shutdown();}
}
