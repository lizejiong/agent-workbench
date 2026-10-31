package com.lzj.rag.backend.search.service.impl;


import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.lzj.rag.backend.document.service.internal.DocumentAccess;
import com.lzj.rag.backend.knowledgebase.dao.entity.KnowledgeBaseDO;
import com.lzj.rag.backend.knowledgebase.exception.KnowledgeBaseException;
import com.lzj.rag.backend.knowledgebase.service.internal.KnowledgeBaseAccess;
import com.lzj.rag.backend.search.channel.SearchChannel;
import com.lzj.rag.backend.search.config.HybridProperties;
import com.lzj.rag.backend.search.config.KeywordProperties;
import com.lzj.rag.backend.search.config.SearchProperties;
import com.lzj.rag.backend.search.postprocess.SearchResultPostProcessor;
import com.lzj.rag.backend.search.service.HybridRetrievalService;
import com.lzj.rag.backend.search.service.KnowledgeSearchService;
import com.lzj.rag.backend.search.service.bo.IndexVersionBO;
import com.lzj.rag.backend.search.service.bo.SearchBO;
import com.lzj.rag.backend.search.service.bo.SearchCandidatesBO;
import com.lzj.rag.backend.search.service.bo.SearchProcessedBO;
import com.lzj.rag.backend.search.service.impl.HybridRetrievalServiceImpl;
import com.lzj.rag.backend.security.model.KnowledgeBasePrincipal;

/** In-process fault injection exercises the real bounded executor; no external-model claims. */
class HybridRetrievalBudgetTest {
    private final KnowledgeSearchService vector=mock(KnowledgeSearchService.class);
    private final KnowledgeBaseAccess bases=mock(KnowledgeBaseAccess.class);
    private final DocumentAccess documents=mock(DocumentAccess.class);
    private final SearchResultPostProcessor post=mock(SearchResultPostProcessor.class);
    private final KnowledgeBasePrincipal actor=new KnowledgeBasePrincipal("alice","unused","tenant-a","alice");
    private final UUID kb=UUID.randomUUID();
    private HybridRetrievalServiceImpl engine;

    @BeforeEach void configure() {
        var base=new KnowledgeBaseDO();base.setId(kb);base.setEmbeddingModelId("qwen-embed");
        base.setEmbeddingConfigRevision("c".repeat(64));base.setEmbeddingAiConfigRevision("a".repeat(64));
        when(bases.requireOwned(actor,kb)).thenReturn(base);
        when(post.process(any(),any(),anyList(),anyString(),anyBoolean(),anyInt(),any()))
                .thenReturn(new SearchProcessedBO(List.of(),"DISABLED"));
    }
    @AfterEach void close(){if(engine!=null)engine.close();}
    private SearchBO request(String strategy){return new SearchBO("database",List.of(kb),null,5,null,strategy,false,null);}
    private HybridRetrievalService engine(boolean keyword,Duration total,Duration vectorTimeout,Duration keywordTimeout,List<SearchChannel> channels) {
        var rerank=new HybridProperties.Rerank(false,"qwen-rerank",Duration.ofSeconds(1),30000,null);
        var config=new HybridProperties("AUTO",50,20,60,1,1,2,vectorTimeout,keywordTimeout,rerank);
        var es=new KeywordProperties(keyword,"http://localhost:9200",null,"kb-test","cjk",Duration.ofSeconds(1),Duration.ofMinutes(1),2000,100,5);
        return engine=new HybridRetrievalServiceImpl(vector,new SearchProperties(50,16,100,4000,total),config,es,bases,documents,post,channels);
    }
    private SearchChannel channel(String name,Callable<SearchCandidatesBO> call) {
        return new SearchChannel(){public String name(){return name;}public SearchCandidatesBO search(KnowledgeBasePrincipal a,SearchBO r,String id,int limit,Instant deadline){try{return call.call();}catch(RuntimeException e){throw e;}catch(Exception e){throw new IllegalStateException(e);}}};
    }
    private static SearchCandidatesBO empty(){return new SearchCandidatesBO(List.of(),false);}
    private static void code(Throwable error,String expected){assertThat(error).isInstanceOfSatisfying(KnowledgeBaseException.class,e->assertThat(e.code()).isEqualTo(expected));}

    @Test void scopeFailurePrecedesEveryChannelAndModelCall() {
        engine(true,Duration.ofSeconds(2),Duration.ofSeconds(1),Duration.ofSeconds(1),List.of());
        when(bases.requireOwned(actor,kb)).thenThrow(new KnowledgeBaseException(404,"KB_NOT_FOUND","Unknown scope"));
        code(catchThrowable(()->engine.retrieve(actor,request("HYBRID"),"denied")),"KB_NOT_FOUND");
        verifyNoInteractions(vector,post,documents);
    }
    @Test void vectorOnlyPreservesProviderErrorsAndPassesTheOuterDeadline() {
        engine(false,Duration.ofSeconds(1),Duration.ofSeconds(2),Duration.ofSeconds(1),List.of());
        Instant start=Instant.now();
        when(vector.recall(eq(actor),any(),anyString(),anyInt(),any())).thenAnswer(call->{
            Instant end=call.getArgument(4);assertThat(end).isBeforeOrEqualTo(start.plusMillis(1100));
            throw new KnowledgeBaseException(502,"KB_MODEL_INVALID_RESPONSE","bad model");
        });
        code(catchThrowable(()->engine.retrieve(actor,request("VECTOR"),"provider")),"KB_MODEL_INVALID_RESPONSE");
    }
    @Test void emptyVectorResultHasCompleteStatusAndAuthorizedVersionTrace() {
        engine(false,Duration.ofSeconds(2),Duration.ofSeconds(1),Duration.ofSeconds(1),List.of());
        when(vector.recall(any(),any(),anyString(),anyInt(),any())).thenReturn(List.of());
        var response=engine.retrieve(actor,request("AUTO"),"empty");
        assertThat(response.status()).isEqualTo("COMPLETE");assertThat(response.outcome()).isEqualTo("NO_EVIDENCE");
        assertThat(response.strategy()).isEqualTo("VECTOR");assertThat(response.trace().requestId()).isEqualTo("empty");
        assertThat(response.trace().indexes()).containsExactly(new IndexVersionBO(kb,"qwen-embed","c".repeat(64),"a".repeat(64)));
        assertThat(response.trace().keywordIndexVersion()).isNull();verifyNoInteractions(post);
    }
    @Test void failedChannelWithEmptySurvivorIsIndeterminateAndBothFailuresAreAnError() {
        engine(true,Duration.ofSeconds(2),Duration.ofSeconds(1),Duration.ofSeconds(1),List.of(
                channel("VECTOR",HybridRetrievalBudgetTest::empty),channel("KEYWORD",()->{throw new IllegalStateException();})));
        var response=engine.retrieve(actor,request("HYBRID"),"partial");
        assertThat(response.strategy()).isEqualTo("VECTOR");assertThat(response.status()).isEqualTo("DEGRADED");assertThat(response.outcome()).isEqualTo("INDETERMINATE");
        engine.close();
        engine(true,Duration.ofSeconds(2),Duration.ofSeconds(1),Duration.ofSeconds(1),List.of(
                channel("VECTOR",()->{throw new IllegalStateException();}),channel("KEYWORD",()->{throw new IllegalStateException();})));
        code(catchThrowable(()->engine.retrieve(actor,request("HYBRID"),"failed")),"KB_SEARCH_UNAVAILABLE");
    }
    @Test void cancelsTheShortChannelBeforeWaitingForTheLongOne()throws Exception {
        var cancelled=new CountDownLatch(1);
        engine(true,Duration.ofSeconds(2),Duration.ofSeconds(1),Duration.ofMillis(80),List.of(
                channel("VECTOR",()->{assertThat(cancelled.await(700,TimeUnit.MILLISECONDS)).isTrue();return empty();}),
                channel("KEYWORD",()->{try{Thread.sleep(5000);return empty();}catch(InterruptedException e){cancelled.countDown();throw e;}})));
        var response=engine.retrieve(actor,request("HYBRID"),"local-timeout");
        assertThat(response.channels()).anySatisfy(state->{assertThat(state.channel()).isEqualTo("KEYWORD");assertThat(state.status()).isEqualTo("TIMEOUT");});
        assertThat(response.strategy()).isEqualTo("VECTOR");
    }
    @Test void evidenceAssemblySharesTheRecallBudgetAndIsCancelledAtTheTotalDeadline()throws Exception {
        var cancelled=new CountDownLatch(1);var passed=new AtomicReference<Instant>();
        engine(true,Duration.ofMillis(400),Duration.ofSeconds(1),Duration.ofSeconds(1),List.of(
                channel("VECTOR",()->{Thread.sleep(120);return empty();}),channel("KEYWORD",HybridRetrievalBudgetTest::empty)));
        when(post.process(any(),any(),anyList(),anyString(),anyBoolean(),anyInt(),any())).thenAnswer(call->{
            passed.set(call.getArgument(6));try{Thread.sleep(5000);}catch(InterruptedException e){cancelled.countDown();throw e;}return null;
        });
        Instant start=Instant.now();long clock=System.nanoTime();
        code(catchThrowable(()->engine.retrieve(actor,request("HYBRID"),"total-timeout")),"KB_SEARCH_TIMEOUT");
        assertThat(Duration.ofNanos(System.nanoTime()-clock)).isLessThan(Duration.ofSeconds(2));
        assertThat(cancelled.await(1,TimeUnit.SECONDS)).isTrue();assertThat(passed.get()).isBeforeOrEqualTo(start.plusMillis(450));
    }
    @Test void cancellationInterruptsEveryOutstandingChannel()throws Exception {
        var started=new CountDownLatch(2);var cancelled=new CountDownLatch(2);var failure=new AtomicReference<Throwable>();
        Callable<SearchCandidatesBO> blocked=()->{started.countDown();try{Thread.sleep(5000);return empty();}catch(InterruptedException e){cancelled.countDown();throw e;}};
        engine(true,Duration.ofSeconds(3),Duration.ofSeconds(2),Duration.ofSeconds(2),List.of(channel("VECTOR",blocked),channel("KEYWORD",blocked)));
        Thread caller=new Thread(()->{try{engine.retrieve(actor,request("HYBRID"),"cancel");}catch(Throwable e){failure.set(e);}});
        caller.start();try{assertThat(started.await(2,TimeUnit.SECONDS)).isTrue();caller.interrupt();caller.join(2000);
            assertThat(cancelled.await(1,TimeUnit.SECONDS)).isTrue();code(failure.get(),"KB_SEARCH_CANCELLED");verifyNoInteractions(post);
        }finally{caller.interrupt();caller.join(2000);}
    }
    @Test void fullExecutorRejectsImmediatelyWithoutAnUnboundedQueue()throws Exception {
        var started=new CountDownLatch(2);var firstRecall=new CountDownLatch(1);var release=new CountDownLatch(1);var callers=Executors.newFixedThreadPool(2);
        engine(false,Duration.ofSeconds(4),Duration.ofSeconds(3),Duration.ofSeconds(1),List.of());
        when(vector.recall(any(),any(),anyString(),anyInt(),any())).thenAnswer(call->{started.countDown();firstRecall.countDown();release.await(3,TimeUnit.SECONDS);return List.of();});
        try {
            var first=callers.submit(()->engine.retrieve(actor,request("VECTOR"),"one"));
            // Let the first request enter recall before the second authorization competes for the other slot.
            assertThat(firstRecall.await(2,TimeUnit.SECONDS)).isTrue();
            var second=callers.submit(()->engine.retrieve(actor,request("VECTOR"),"two"));
            assertThat(started.await(2,TimeUnit.SECONDS)).isTrue();
            code(catchThrowable(()->engine.retrieve(actor,request("VECTOR"),"overload")),"KB_SEARCH_CAPACITY");
            release.countDown();first.get(2,TimeUnit.SECONDS);second.get(2,TimeUnit.SECONDS);
        }finally{release.countDown();callers.shutdownNow();}
    }
    @Test void indexConfigurationChangesDuringRecallCannotProduceAMisleadingTrace() {
        engine(false,Duration.ofSeconds(2),Duration.ofSeconds(1),Duration.ofSeconds(1),List.of());
        when(vector.recall(any(),any(),anyString(),anyInt(),any())).thenAnswer(call->{bases.requireOwned(actor,kb).setEmbeddingAiConfigRevision("b".repeat(64));return List.of();});
        code(catchThrowable(()->engine.retrieve(actor,request("VECTOR"),"changed")),"KB_SEARCH_SCOPE_CHANGED");
    }
    @Test void authorizationWaitingAlsoConsumesTheTotalBudget()throws Exception {
        var cancelled=new CountDownLatch(1);
        engine(false,Duration.ofMillis(100),Duration.ofSeconds(1),Duration.ofSeconds(1),List.of());
        when(bases.requireOwned(actor,kb)).thenAnswer(call->{try{Thread.sleep(5000);}catch(InterruptedException e){cancelled.countDown();throw e;}return null;});
        code(catchThrowable(()->engine.retrieve(actor,request("VECTOR"),"auth-timeout")),"KB_SEARCH_TIMEOUT");
        assertThat(cancelled.await(1,TimeUnit.SECONDS)).isTrue();verifyNoInteractions(vector,post);
    }
}
