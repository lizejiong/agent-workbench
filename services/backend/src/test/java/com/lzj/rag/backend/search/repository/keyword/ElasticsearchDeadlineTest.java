package com.lzj.rag.backend.search.repository.keyword;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.net.InetSocketAddress;
import java.time.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import com.lzj.rag.backend.knowledgebase.exception.KnowledgeBaseException;
import com.lzj.rag.backend.search.config.KeywordProperties;
import com.lzj.rag.backend.search.repository.keyword.ElasticsearchKeywordIndex;

/** Actual Java Client HTTP transport to a stalled local fixture, not an Elasticsearch quality test. */
class ElasticsearchDeadlineTest {
    private HttpServer server;
    private final ExecutorService handlers=Executors.newFixedThreadPool(2);
    private final CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);
    private ElasticsearchKeywordIndex index;
    @BeforeEach void start()throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(handlers);
        server.createContext("/",exchange->{started.countDown();try{release.await(3,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}finally{exchange.close();}});
        server.start();
        var config=new KeywordProperties(true,"http://127.0.0.1:"+server.getAddress().getPort(),null,"kb-test","cjk",Duration.ofSeconds(3),Duration.ofMinutes(1),2000,100,5);
        index=new ElasticsearchKeywordIndex(config,new ObjectMapper());
    }
    @AfterEach void stop()throws Exception {release.countDown();index.close();server.stop(0);handlers.shutdownNow();}
    @Test void firstUseReadinessCannotOutliveTheQueryDeadline() {
        long start=System.nanoTime();
        assertThatThrownBy(()->index.query("database","a","alice",List.of("doc:1"),5,Instant.now().plusMillis(150)))
                .isInstanceOfSatisfying(KnowledgeBaseException.class,e->assertThat(e.code()).isEqualTo("KB_KEYWORD_TIMEOUT"));
        assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofSeconds(2));
    }
    @Test void interruptingReadinessReleasesItsLockForTheNextCaller()throws Exception {
        var failure=new AtomicReference<Throwable>();
        Thread caller=new Thread(()->{try{index.query("database","a","alice",List.of("doc:1"),5,Instant.now().plusSeconds(3));}catch(Throwable e){failure.set(e);}});
        caller.start();try {
            assertThat(started.await(2,TimeUnit.SECONDS)).isTrue();caller.interrupt();caller.join(1000);
            assertThat(caller.isAlive()).isFalse();assertThat(failure.get()).isInstanceOf(KnowledgeBaseException.class);
            long start=System.nanoTime();
            assertThatThrownBy(()->index.query("next","a","alice",List.of("doc:1"),5,Instant.now().plusMillis(150)))
                    .isInstanceOfSatisfying(KnowledgeBaseException.class,e->assertThat(e.code()).isEqualTo("KB_KEYWORD_TIMEOUT"));
            assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofSeconds(1));
        }finally{caller.interrupt();caller.join(1000);}
    }
}
