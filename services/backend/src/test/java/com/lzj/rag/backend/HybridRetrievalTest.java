package com.lzj.rag.backend;

import com.fasterxml.jackson.databind.*;
import com.lzj.rag.backend.document.*;
import com.lzj.rag.backend.ingestion.*;
import com.lzj.rag.backend.knowledgebase.KnowledgeBaseException;
import com.lzj.rag.backend.model.EmbeddingStub;
import com.lzj.rag.backend.search.*;
import com.lzj.rag.backend.security.KnowledgeBasePrincipal;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real Elasticsearch BM25/analyzer + PG/S3. Only AI responses and injected transport failures are fixtures. */
@SpringBootTest(properties={"kb.storage.enabled=true","kb.storage.bucket=kb-hybrid-test","kb.keyword.enabled=true",
        "kb.retrieval.rerank.enabled=true","kb.retrieval.rerank.timeout=500ms","kb.retrieval.keyword-timeout=2s","kb.ai.call-timeout=1s"})
@AutoConfigureMockMvc @ActiveProfiles("test") @Import({DocumentUploadTest.StorageTestConfiguration.class,HybridRetrievalTest.IndexConfiguration.class})
@Testcontainers(disabledWithoutDocker=true)
class HybridRetrievalTest {
    static EmbeddingStub model;static synchronized String modelUrl(){if(model==null)model=new EmbeddingStub();return model.url();}
    @AfterAll static void close(){if(model!=null)model.close();}
    @Container static final PostgreSQLContainer<?> DATABASE=new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:0.8.7-pg17-bookworm").asCompatibleSubstituteFor("postgres"));
    @Container static final GenericContainer<?> MINIO=new GenericContainer<>(DockerImageName.parse("minio/minio:RELEASE.2025-09-07T16-13-09Z"))
            .withEnv("MINIO_ROOT_USER","kb-test-access").withEnv("MINIO_ROOT_PASSWORD","kb-test-secret-password").withCommand("server","/data").withExposedPorts(9000).waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));
    @Container static final GenericContainer<?> ELASTIC=new GenericContainer<>(DockerImageName.parse(System.getProperty("kb.test.elasticsearch-image","docker.elastic.co/elasticsearch/elasticsearch:8.19.17")))
            .withEnv("discovery.type","single-node").withEnv("xpack.security.enabled","false").withEnv("xpack.ml.enabled","false")
            .withEnv("ingest.geoip.downloader.enabled","false").withEnv("ES_JAVA_OPTS","-Xms512m -Xmx512m")
            .withExposedPorts(9200).waitingFor(Wait.forHttp("/").forPort(9200).withStartupTimeout(Duration.ofMinutes(2)));
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",DATABASE::getJdbcUrl);p.add("spring.datasource.username",DATABASE::getUsername);p.add("spring.datasource.password",DATABASE::getPassword);
        p.add("kb.storage.endpoint",()->"http://"+MINIO.getHost()+":"+MINIO.getMappedPort(9000));p.add("kb.storage.path-style-access",()->true);
        p.add("kb.ai.base-url",HybridRetrievalTest::modelUrl);p.add("kb.keyword.endpoint",()->"http://"+ELASTIC.getHost()+":"+ELASTIC.getMappedPort(9200));
    }
    @TestConfiguration(proxyBeanMethods=false) static class IndexConfiguration {
        @Bean @Primary FaultIndex faultIndex(ElasticsearchKeywordIndex delegate){return new FaultIndex(delegate);}
    }
    static class FaultIndex implements KeywordIndex {
        final ElasticsearchKeywordIndex delegate;volatile boolean failWrite,failQuery,failDelete,slowQuery;
        volatile Runnable beforeWrite=()->{},beforeQuery=()->{};
        volatile Throwable lastQueryFailure;
        FaultIndex(ElasticsearchKeywordIndex delegate){this.delegate=delegate;}
        void reset(){failWrite=failQuery=failDelete=slowQuery=false;beforeWrite=()->{};beforeQuery=()->{};}
        public void ready(){delegate.ready();}
        public void write(List<IndexedChunk> rows,Instant deadline){beforeWrite.run();if(failWrite)throw new KnowledgeBaseException(503,"KB_KEYWORD_UNAVAILABLE","injected");delegate.write(rows,deadline);}
        public List<Hit> query(String q,String t,String o,List<String> keys,int limit,Instant deadline){beforeQuery.run();if(failQuery)throw new KnowledgeBaseException(503,"KB_KEYWORD_UNAVAILABLE","injected");if(slowQuery)try{Thread.sleep(5000);}catch(InterruptedException e){Thread.currentThread().interrupt();}try{return delegate.query(q,t,o,keys,limit,deadline);}catch(RuntimeException e){lastQueryFailure=e.getCause()==null?e:e.getCause();throw e;}}
        public void deleteBefore(UUID doc,long rev,Instant deadline){if(failDelete)throw new KnowledgeBaseException(503,"KB_KEYWORD_UNAVAILABLE","injected");delegate.deleteBefore(doc,rev,deadline);}
    }
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired JdbcTemplate jdbc;@Autowired IngestionKernel kernel;
    @Autowired UserDetailsService users;@Autowired KeywordSyncService sync;@Autowired FaultIndex index;@Autowired KeywordProperties keyword;
    @Autowired DocumentUploadTest.FaultStorage storage;@Autowired DocumentCleanupService deletion;
    @BeforeEach void clear(){model.reset();model.vectorizer=KnowledgeSearchTest::topicVector;storage.reset();index.reset();TestDatabaseCleanup.clear(jdbc);}
    private KnowledgeBasePrincipal actor(){return (KnowledgeBasePrincipal)users.loadUserByUsername("alice");}
    private JsonNode call(MockHttpServletRequestBuilder request,Object body,int status)throws Exception{return call(request,body,status,"alice");}
    private JsonNode call(MockHttpServletRequestBuilder request,Object body,int status,String user)throws Exception {
        if(body!=null)request.contentType("application/json").content(json.writeValueAsBytes(body));
        var response=mvc.perform(request.with(httpBasic(user,user+"-local-test-password"))).andReturn().getResponse();assertThat(response.getStatus()).as("HTTP result; test keyword diagnostic: %s",index.lastQueryFailure).isEqualTo(status);return json.readTree(response.getContentAsByteArray()).path("data");
    }
    private UUID base()throws Exception{return UUID.fromString(call(post("/api/kbs"),Map.of("name","hybrid"),201).path("id").asText());}
    private UUID document(UUID kb,String filename,String body)throws Exception {
        var file=new MockMultipartFile("file",filename,"text/plain",body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        UUID doc=UUID.fromString(call(multipart("/api/kbs/"+kb+"/documents").file(file).header("Idempotency-Key",UUID.randomUUID().toString()),null,201).path("id").asText());kernel.ingest(actor(),doc);return doc;
    }
    private List<UUID> fixture(UUID kb)throws Exception{return List.of(document(kb,"manual.md","# 项目运维\n\n专有产品麒麟数据库，错误编号 ZX-1472，执行 PostgreSQL 备份。"),document(kb,"recovery.md","# 恢复指南\n\nDatabase backup uses pg_dump and durable snapshots."),document(kb,"cooking.md","# Kitchen\n\nBaking bread with flour."));}
    private void syncAll(){for(int i=0;i<20 && jdbc.queryForObject("SELECT count(*) FROM kb_keyword_sync WHERE status='PENDING'",Integer.class)>0;i++)sync.runOnce();}
    private JsonNode retrieve(UUID kb,String q,String strategy,boolean rerank)throws Exception{return call(post("/api/knowledge/retrieve"),Map.of("query",q,"kbIds",List.of(kb),"strategy",strategy,"rerank",rerank),200);}
    private long version(UUID doc){return jdbc.queryForObject("SELECT version FROM kb_document WHERE id=?",Long.class,doc);}
    private UUID chunk(UUID doc){return jdbc.queryForObject("SELECT id FROM kb_chunk WHERE document_id=? AND generation=(SELECT active_generation FROM kb_document WHERE id=?) ORDER BY chunk_index LIMIT 1",UUID.class,doc,doc);}
    @Test void realBm25FindsChineseTermsAndIdentifiersAndPreservesActualSources()throws Exception {
        UUID kb=base();var docs=fixture(kb);syncAll();
        var numbered=retrieve(kb,"ZX-1472","KEYWORD",false);assertThat(numbered.path("evidence")).hasSize(1);
        var hit=numbered.path("evidence").get(0);assertThat(hit.path("documentId").asText()).isEqualTo(docs.get(0).toString());assertThat(hit.path("content").asText()).contains("麒麟数据库");
        assertThat(hit.at("/channelScores/0/scoreType").asText()).isEqualTo("BM25");assertThat(hit.at("/channelScores/0/score").asDouble()).isGreaterThan(0);assertThat(hit.at("/sourceMetadata/chapterPath").toString()).contains("项目运维");
        assertThat(hit.path("fileUrl").asText()).isEqualTo("/api/documents/"+docs.get(0)+"/file");assertThat(hit.path("sourceMetadata").has("pageNumber")).isFalse();
        assertThat(retrieve(kb,"麒麟数据库","KEYWORD",false).path("evidence")).hasSize(1);assertThat(retrieve(kb,"ABSENT_987654321","KEYWORD",false).path("evidence")).isEmpty();
        var exported=Map.of("environment","Actual ES8.19.17/cjk BM25, PostgreSQL and MinIO; AI HTTP fixture","request",Map.of("query","ZX-1472","kbIds",List.of(kb),"strategy","KEYWORD","rerank",false),"response",numbered);
        Files.createDirectories(Path.of("target/knowledge-base"));Files.writeString(Path.of("target/knowledge-base/hybrid-example.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(exported));
    }
    @Test void crossChannelDuplicatesAreFusedByRanksAndNeverByAddingIncompatibleRawScores()throws Exception {
        UUID kb=base();fixture(kb);syncAll();var response=retrieve(kb,"database backup","HYBRID",false);var hits=response.path("evidence");assertThat(hits).isNotEmpty();
        var seen=new HashSet<String>();boolean duplicate=false;
        for(var hit:hits){assertThat(seen.add(hit.path("chunkId").asText())).isTrue();double sum=0;for(var score:hit.path("channelScores"))sum+=1.0/(60+score.path("rank").asInt());assertThat(hit.path("fusionScore").asDouble()).isCloseTo(sum,within(1e-12));assertThat(hit.path("scoreType").asText()).isEqualTo("RRF");if(hit.path("channelScores").size()==2)duplicate=true;}
        assertThat(duplicate).isTrue();assertThat(response.path("strategy").asText()).isEqualTo("KEYWORD+VECTOR");
    }
    @Test void semanticVectorEvidenceRemainsAvailableWhenKeywordsHaveNoMatchingTokens()throws Exception {
        UUID kb=base();fixture(kb);syncAll();var response=retrieve(kb,"database connection configuration","HYBRID",false);
        assertThat(response.path("evidence")).isNotEmpty();assertThat(response.path("channels")).hasSize(2);assertThat(model.requests).isNotEmpty();
    }
    @Test void keywordFailureOrTimeoutKeepsVectorResultsAndReportsTheActualStrategy()throws Exception {
        UUID kb=base();fixture(kb);syncAll();index.failQuery=true;var failed=retrieve(kb,"database","HYBRID",false);assertThat(failed.path("strategy").asText()).isEqualTo("VECTOR");assertThat(failed.path("evidence")).isNotEmpty();
        index.failQuery=false;index.slowQuery=true;var timed=retrieve(kb,"database","HYBRID",false);assertThat(timed.path("strategy").asText()).isEqualTo("VECTOR");assertThat(timed.path("channels").toString()).contains("TIMEOUT");
    }
    @Test void vectorFailureKeepsBm25AndAllChannelFailureIsAnErrorRatherThanEmptyEvidence()throws Exception {
        UUID kb=base();fixture(kb);syncAll();model.mode="WRONG_DIM";var result=retrieve(kb,"ZX-1472","HYBRID",false);assertThat(result.path("strategy").asText()).isEqualTo("KEYWORD");assertThat(result.path("evidence")).hasSize(1);
        index.failQuery=true;call(post("/api/knowledge/retrieve"),Map.of("query","ZX-1472","kbIds",List.of(kb),"strategy","HYBRID","rerank",false),503);
    }
    @Test void rerankMapsUnorderedIndicesAndKeepsFusionAndOriginalScoresSeparate()throws Exception {
        UUID kb=base();fixture(kb);syncAll();var response=retrieve(kb,"database backup","HYBRID",true);assertThat(response.path("rerankStatus").asText()).isEqualTo("APPLIED");assertThat(model.rerankRequests).hasSize(1);
        var request=model.rerankRequests.get(0);var hits=response.path("evidence");int count=request.path("documents").size();
        assertThat(hits.get(0).path("chunkId").asText()).isEqualTo(request.path("documents").get(count-1).path("id").asText());assertThat(hits.get(0).path("rerankScore").asDouble()).isGreaterThan(hits.get(count-1).path("rerankScore").asDouble());
        assertThat(hits.get(0).path("score")).isEqualTo(hits.get(0).path("fusionScore"));assertThat(hits.get(0).path("rerankScore")).isNotEqualTo(hits.get(0).path("fusionScore"));assertThat(request.path("model").asText()).isEqualTo("qwen-rerank");
    }
    @Test void malformedOrTimeoutRerankFallsBackToExactlyTheFusionOrder()throws Exception {
        UUID kb=base();fixture(kb);syncAll();var expected=retrieve(kb,"database backup","HYBRID",false).path("evidence");
        for(String mode:List.of("DUPLICATE","OUT_OF_RANGE","MISSING_SCORE","SHORT","WRONG_ID","WRONG_MODEL","NON_NUMERIC","TIMEOUT")) {
            model.rerankMode=mode;var result=retrieve(kb,"database backup","HYBRID",true);assertThat(result.path("rerankStatus").asText()).isEqualTo("FALLBACK_FUSION");
            var actualStable=result.path("evidence").deepCopy();var expectedStable=expected.deepCopy();
            // Lucene segment merges may change corpus BM25 statistics between requests; fusion order/ranks remain the contract.
            for(var array:List.of(actualStable,expectedStable))for(var row:array)for(var score:row.path("channelScores"))((com.fasterxml.jackson.databind.node.ObjectNode)score).remove("score");
            assertThat(actualStable).isEqualTo(expectedStable);for(var row:result.path("evidence"))assertThat(row.path("rerankScore").isNull()).isTrue();
        }
    }
    @Test void rerankThresholdFailsClosedWhenNoRerankScoresExistAndDoesNotPretendRrfIsConfidence()throws Exception {
        UUID kb=base();fixture(kb);syncAll();model.rerankMode="SHORT";
        var result=call(post("/api/knowledge/retrieve"),Map.of("query","database","kbIds",List.of(kb),"minRerankScore",0.6),200);assertThat(result.path("evidence")).isEmpty();assertThat(result.path("rerankStatus").asText()).isEqualTo("THRESHOLD_UNAVAILABLE");
        call(post("/api/knowledge/retrieve"),Map.of("query","database","kbIds",List.of(kb),"minRerankScore",0.6,"rerank",false),400);
        call(post("/api/knowledge/retrieve"),Map.of("query","database","kbIds",List.of(kb),"minScore",0.6),400);
        model.rerankMode="GOOD";var filtered=call(post("/api/knowledge/retrieve"),Map.of("query","database","kbIds",List.of(kb),"minRerankScore",0.6),200);
        assertThat(filtered.path("evidence")).hasSize(1);assertThat(filtered.path("evidence").get(0).path("rerankScore").asDouble()).isGreaterThanOrEqualTo(0.6);
    }
    @Test void keywordIndexUpdatesAreTransactionalDurableAndRetriedAfterFailure()throws Exception {
        UUID kb=base();UUID doc=document(kb,"guide.md","# Guide\n\nZX-1472 database.");index.failWrite=true;assertThat(sync.runOnce()).isTrue();
        assertThat(sync.get(actor(),doc).status()).isEqualTo("PENDING");assertThat(sync.get(actor(),doc).attempts()).isEqualTo(1);assertThat(retrieve(kb,"ZX-1472","KEYWORD",false).path("evidence")).isEmpty();
        index.failWrite=false;jdbc.update("UPDATE kb_keyword_sync SET next_run_at=clock_timestamp() WHERE document_id=?",doc);syncAll();assertThat(sync.get(actor(),doc).status()).isEqualTo("SUCCEEDED");
        call(patch("/api/documents/"+doc+"/chunks/"+chunk(doc)),Map.of("version",version(doc),"content","Updated identifier NEW-9988."),200);
        assertThat(retrieve(kb,"ZX-1472","KEYWORD",false).path("evidence")).isEmpty();syncAll();assertThat(retrieve(kb,"NEW-9988","KEYWORD",false).path("evidence")).hasSize(1);
    }
    @Test void staleIndexTextIsNeverSentToRerankOrReturnedAfterDisableOrDelete()throws Exception {
        UUID kb=base();var docs=fixture(kb);syncAll();UUID doc=docs.get(0);call(patch("/api/documents/"+doc+"/enabled"),Map.of("version",version(doc),"enabled",false),200);
        assertThat(retrieve(kb,"ZX-1472","KEYWORD",true).path("evidence")).isEmpty();assertThat(model.rerankRequests).isEmpty();
        UUID other=docs.get(1);model.beforeRerank=()->{model.beforeRerank=()->{};deletion.delete(actor(),other,version(other));};
        var result=retrieve(kb,"database backup","HYBRID",true);for(var row:result.path("evidence"))assertThat(row.path("documentId").asText()).isNotEqualTo(other.toString());
    }
    @Test void authorizationAndScopeAreCheckedBeforeEitherExternalChannel()throws Exception {
        UUID kb=base();var docs=fixture(kb);syncAll();int count=model.requests.size();
        index.beforeQuery=()->{throw new AssertionError("No ES query for unauthorized scope");};
        call(post("/api/knowledge/retrieve"),Map.of("query","database","kbIds",List.of(kb)),404,"bob");call(post("/api/knowledge/retrieve"),Map.of("query","database","kbIds",List.of(kb)),404,"carol");
        mvc.perform(post("/api/knowledge/retrieve").contentType("application/json").content("{} ")).andExpect(status().isUnauthorized());assertThat(model.requests).hasSize(count);
        index.beforeQuery=()->{};var result=call(post("/api/knowledge/retrieve"),Map.of("query","database","kbIds",List.of(kb),"documentIds",List.of(docs.get(1)),"strategy","KEYWORD","rerank",false),200);
        for(var row:result.path("evidence"))assertThat(row.path("documentId").asText()).isEqualTo(docs.get(1).toString());
    }
    @Test void lostSyncLeaseAndSupersededRevisionCannotBecomeVisible()throws Exception {
        UUID kb=base();UUID doc=document(kb,"guide.md","# Guide\n\nZX-1472 database.");var old=sync.claimNext(false);assertThat(old).isNotNull();
        index.beforeWrite=()->{index.beforeWrite=()->{};jdbc.update("UPDATE kb_keyword_sync SET lease_until=clock_timestamp()-interval '1 second' WHERE document_id=?",doc);};sync.execute(old);assertThat(sync.get(actor(),doc).syncedRevision()).isZero();
        sync.execute(sync.claimNext(false));assertThat(sync.get(actor(),doc).status()).isEqualTo("SUCCEEDED");
        sync.retry(actor(),kb,doc);var claim=sync.claimNext(false);index.beforeWrite=()->{index.beforeWrite=()->{};try{call(patch("/api/documents/"+doc+"/chunks/"+chunk(doc)),Map.of("version",version(doc),"content","REPLACED-7788"),200);}catch(Exception e){throw new IllegalStateException(e);}};
        sync.execute(claim);assertThat(sync.get(actor(),doc).status()).isEqualTo("PENDING");syncAll();assertThat(retrieve(kb,"REPLACED-7788","KEYWORD",false).path("evidence")).hasSize(1);
    }
    @Test void obsoleteRecordsCannotConsumeQuotaAndGarbageCleanupRetriesDurably()throws Exception {
        UUID kb=base();UUID doc=document(kb,"guide.md","# Guide\n\nORIGINAL-1472 database.");syncAll();
        for(int i=0;i<7;i++){call(patch("/api/documents/"+doc+"/chunks/"+chunk(doc)),Map.of("version",version(doc),"content","CURRENT-9988 database revision "+i),200);syncAll();}
        assertThat(retrieve(kb,"ORIGINAL-1472","KEYWORD",false).path("evidence")).isEmpty();assertThat(retrieve(kb,"CURRENT-9988","KEYWORD",false).path("evidence")).hasSize(1);
        index.failDelete=true;jdbc.update("UPDATE kb_keyword_cleanup SET next_run_at=clock_timestamp() WHERE document_id=?",doc);sync.runOnce();assertThat(jdbc.queryForObject("SELECT status FROM kb_keyword_cleanup WHERE document_id=?",String.class,doc)).isEqualTo("PENDING");
        index.failDelete=false;jdbc.update("UPDATE kb_keyword_cleanup SET next_run_at=clock_timestamp() WHERE document_id=?",doc);sync.runOnce();assertThat(jdbc.queryForObject("SELECT status FROM kb_keyword_cleanup WHERE document_id=?",String.class,doc)).isEqualTo("SUCCEEDED");
        assertThat(retrieve(kb,"CURRENT-9988","KEYWORD",false).path("evidence")).hasSize(1);
    }
    @Test void boundedFailuresStopAndOwnedRetryWorksEvenAfterTombstone()throws Exception {
        UUID kb=base();UUID doc=document(kb,"guide.md","# Guide\n\nZX-1472 database.");index.failWrite=true;
        for(int i=0;i<keyword.maxAttempts();i++){sync.runOnce();jdbc.update("UPDATE kb_keyword_sync SET next_run_at=clock_timestamp() WHERE document_id=?",doc);}
        assertThat(sync.get(actor(),doc).status()).isEqualTo("FAILED");deletion.delete(actor(),doc,version(doc));index.failWrite=false;
        call(post("/api/kbs/"+kb+"/keyword-sync/"+doc+"/retry"),null,200);syncAll();assertThat(call(get("/api/kbs/"+kb+"/keyword-sync"),null,200).path("records").get(0).path("status").asText()).isEqualTo("SUCCEEDED");
        call(post("/api/kbs/"+kb+"/keyword-sync/"+doc+"/retry"),null,404,"bob");
    }
    @Test void originalSearchRemainsAnArrayAndExposesStrategyEvenForEmptyEvidence()throws Exception {
        UUID kb=base();fixture(kb);syncAll();
        var response=mvc.perform(post("/api/knowledge/search").with(httpBasic("alice","alice-local-test-password")).contentType("application/json").content(json.writeValueAsBytes(Map.of("query","ABSENT_987654321","kbIds",List.of(kb),"strategy","KEYWORD","rerank",false))))
                .andExpect(status().isOk()).andExpect(header().string("X-KB-Search-Strategy","KEYWORD")).andReturn().getResponse();assertThat(json.readTree(response.getContentAsByteArray()).path("data").isArray()).isTrue();
        call(post("/api/knowledge/retrieve"),Map.of("query","database","kbIds",List.of(kb),"topK",51),400);
    }
    @Test void outboxFailureRollsBackTheContentAndVectorChangeRatherThanLosingTheSyncIntent()throws Exception {
        UUID kb=base();UUID doc=document(kb,"guide.md","# Guide\n\nORIGINAL-1472 database.");syncAll();var before=sync.get(actor(),doc);
        jdbc.execute("CREATE FUNCTION reject_keyword_intent() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'injected'; END $$");
        jdbc.execute("CREATE TRIGGER reject_keyword_intent BEFORE UPDATE ON kb_keyword_sync FOR EACH ROW EXECUTE FUNCTION reject_keyword_intent()");
        try{call(patch("/api/documents/"+doc+"/chunks/"+chunk(doc)),Map.of("version",version(doc),"content","NEVER-9988"),503);}finally{jdbc.execute("DROP TRIGGER reject_keyword_intent ON kb_keyword_sync");jdbc.execute("DROP FUNCTION reject_keyword_intent()");}
        assertThat(sync.get(actor(),doc).revision()).isEqualTo(before.revision());assertThat(retrieve(kb,"ORIGINAL-1472","KEYWORD",false).path("evidence")).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT content FROM kb_chunk WHERE id=?",String.class,chunk(doc))).contains("ORIGINAL");
    }
    @Test void reingestionSwitchesKeywordVisibilityToTheNewGenerationOnlyAfterItsSync()throws Exception {
        UUID kb=base();UUID doc=document(kb,"guide.md","# Guide\n\nZX-1472 database.");syncAll();UUID old=chunk(doc);kernel.ingest(actor(),doc);
        assertThat(retrieve(kb,"ZX-1472","KEYWORD",false).path("evidence")).isEmpty();
        var vector=retrieve(kb,"database","HYBRID",false);assertThat(vector.path("evidence").get(0).path("generation").asLong()).isEqualTo(2);
        syncAll();var keywordResult=retrieve(kb,"ZX-1472","KEYWORD",false).path("evidence");assertThat(keywordResult).hasSize(1);assertThat(keywordResult.get(0).path("chunkId").asText()).isNotEqualTo(old.toString());assertThat(keywordResult.get(0).path("generation").asLong()).isEqualTo(2);
    }
    @Test void documentReenablePreservesIndividuallyDisabledChunksAndChunkDeletionSynchronizes()throws Exception {
        UUID kb=base();UUID doc=document(kb,"guide.md","# Guide\n\nZX-1472 database.");syncAll();UUID chunk=chunk(doc);
        call(patch("/api/documents/"+doc+"/chunks/"+chunk+"/enabled"),Map.of("version",version(doc),"enabled",false),200);syncAll();
        call(patch("/api/documents/"+doc+"/enabled"),Map.of("version",version(doc),"enabled",false),200);syncAll();
        call(patch("/api/documents/"+doc+"/enabled"),Map.of("version",version(doc),"enabled",true),200);syncAll();assertThat(retrieve(kb,"ZX-1472","KEYWORD",false).path("evidence")).isEmpty();
        call(patch("/api/documents/"+doc+"/chunks/"+chunk+"/enabled"),Map.of("version",version(doc),"enabled",true),200);syncAll();assertThat(retrieve(kb,"ZX-1472","KEYWORD",false).path("evidence")).hasSize(1);
        call(delete("/api/documents/"+doc+"/chunks/"+chunk).param("version",version(doc)+""),null,200);assertThat(retrieve(kb,"ZX-1472","KEYWORD",false).path("evidence")).isEmpty();syncAll();assertThat(sync.get(actor(),doc).status()).isEqualTo("SUCCEEDED");
    }
    @Test void vectorAndKeywordChannelsActuallyStartInParallel()throws Exception {
        UUID kb=base();fixture(kb);syncAll();var barrier=new CyclicBarrier(2);
        Runnable await=()->{try{barrier.await(750,TimeUnit.MILLISECONDS);}catch(Exception e){throw new IllegalStateException("Channels did not overlap",e);}};
        model.beforeEmbed=await;index.beforeQuery=await;
        try{var result=retrieve(kb,"database","HYBRID",false);assertThat(result.path("strategy").asText()).isEqualTo("KEYWORD+VECTOR");}
        finally{model.beforeEmbed=()->{};index.beforeQuery=()->{};}
    }
}
