package com.lzj.rag.backend;

import com.fasterxml.jackson.databind.*;
import com.lzj.rag.backend.document.*;
import com.lzj.rag.backend.ingestion.*;
import com.lzj.rag.backend.knowledgebase.KnowledgeBaseException;
import com.lzj.rag.backend.model.EmbeddingStub;
import com.lzj.rag.backend.security.KnowledgeBasePrincipal;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"kb.storage.enabled=true","kb.storage.bucket=kb-maintenance-test","kb.storage.max-upload-bytes=4096","kb.maintenance.max-batch-ids=2"})
@AutoConfigureMockMvc @ActiveProfiles("test") @Import(DocumentUploadTest.StorageTestConfiguration.class)
@Testcontainers(disabledWithoutDocker=true)
class ContentMaintenanceTest {
    static EmbeddingStub model;
    static synchronized String modelUrl(){if(model==null)model=new EmbeddingStub();return model.url();}
    @AfterAll static void stop(){if(model!=null)model.close();}
    @Container static final PostgreSQLContainer<?> DATABASE=new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:0.8.7-pg17-bookworm").asCompatibleSubstituteFor("postgres"));
    @Container static final GenericContainer<?> MINIO=new GenericContainer<>(DockerImageName.parse("minio/minio:RELEASE.2025-09-07T16-13-09Z"))
            .withEnv("MINIO_ROOT_USER","kb-test-access").withEnv("MINIO_ROOT_PASSWORD","kb-test-secret-password")
            .withCommand("server","/data").withExposedPorts(9000).waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));
    @DynamicPropertySource static void infrastructure(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",DATABASE::getJdbcUrl);p.add("spring.datasource.username",DATABASE::getUsername);p.add("spring.datasource.password",DATABASE::getPassword);
        p.add("kb.storage.endpoint",()->"http://"+MINIO.getHost()+":"+MINIO.getMappedPort(9000));p.add("kb.storage.path-style-access",()->true);
        p.add("kb.ai.base-url",ContentMaintenanceTest::modelUrl);p.add("kb.ai.call-timeout",()->"1s");
    }
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired JdbcTemplate jdbc;@Autowired IngestionKernel kernel;
    @Autowired IngestionJobService jobs;@Autowired UserDetailsService users;@Autowired DocumentCleanupService cleanup;
    @Autowired DocumentUploadTest.FaultStorage storage;
    @BeforeEach void clear(){model.reset();model.vectorizer=KnowledgeSearchTest::topicVector;storage.reset();TestDatabaseCleanup.clear(jdbc);}
    private KnowledgeBasePrincipal actor(){return (KnowledgeBasePrincipal)users.loadUserByUsername("alice");}
    private JsonNode call(MockHttpServletRequestBuilder request,Object body,int status)throws Exception {
        if(body!=null)request.contentType("application/json").content(json.writeValueAsString(body));
        return json.readTree(mvc.perform(request.with(httpBasic("alice","alice-local-test-password"))).andExpect(status().is(status)).andReturn().getResponse().getContentAsString()).path("data");
    }
    private UUID base()throws Exception{return UUID.fromString(call(post("/api/kbs"),Map.of("name","maintenance-"+UUID.randomUUID(),"embeddingProfileId","qwen-embed-1024"),201).path("id").asText());}
    private UUID document(UUID kb,String name,boolean ingest)throws Exception {
        var f=new MockMultipartFile("file",name,"text/plain","Database PostgreSQL connection evidence.".getBytes(StandardCharsets.UTF_8));
        UUID id=UUID.fromString(call(multipart("/api/kbs/"+kb+"/documents").file(f).header("Idempotency-Key",UUID.randomUUID().toString()),null,201).path("id").asText());
        if(ingest)kernel.ingest(actor(),id);return id;
    }
    private UUID fixture()throws Exception{return document(base(),"database.txt",true);}
    private long version(UUID doc){return jdbc.queryForObject("SELECT version FROM kb_document WHERE id=?",Long.class,doc);}
    private UUID chunk(UUID doc){return jdbc.queryForObject("SELECT id FROM kb_chunk WHERE document_id=? ORDER BY chunk_index LIMIT 1",UUID.class,doc);}
    private String path(UUID doc){return "/api/documents/"+doc;}
    private JsonNode hits(UUID doc,String query)throws Exception {
        UUID kb=jdbc.queryForObject("SELECT kb_id FROM kb_document WHERE id=?",UUID.class,doc);
        return call(post("/api/knowledge/search"),Map.of("query",query,"kbIds",List.of(kb),"minScore",0.95),200);
    }
    @Test void editsReplaceSemanticVectorAndDoNotPretendToRetainFileCoordinates()throws Exception {
        UUID doc=fixture(),chunk=chunk(doc);assertThat(hits(doc,"database").size()).isEqualTo(1);
        var changed=call(patch(path(doc)+"/chunks/"+chunk),Map.of("version",version(doc),"content","Baking bread with yeast.\r\n人工补充"),200);
        assertThat(changed.path("documentVersion").asLong()).isEqualTo(2);var c=changed.path("chunks").get(0);
        assertThat(c.path("content").asText()).isEqualTo(c.path("embeddingText").asText()).doesNotContain("\r");
        assertThat(c.path("edited").asBoolean()).isTrue();assertThat(c.path("tokenCountEstimated").asBoolean()).isTrue();assertThat(c.path("tokenCount").asInt()).isPositive();
        assertThat(hits(doc,"database").isEmpty()).isTrue();var found=hits(doc,"baking");assertThat(found.size()).isEqualTo(1);
        assertThat(found.get(0).at("/sourceMetadata/manual").asBoolean()).isTrue();assertThat(found.get(0).at("/sourceMetadata/originalFileContainsContent").asBoolean()).isFalse();
        assertThat(found.get(0).at("/sourceMetadata/sourceBlocks").isEmpty()).isTrue();
        var audit=jdbc.queryForMap("SELECT * FROM kb_content_audit WHERE document_id=? AND action='CHUNK_EDIT'",doc);
        assertThat(audit.get("actor_id")).isEqualTo("user-alice");assertThat(audit.get("before_summary").toString()).doesNotContain("PostgreSQL");
        assertThat(audit.get("after_summary").toString()).doesNotContain("yeast");
    }
    @Test void addsDeletesAndReusesEmptyGenerationWithoutReusingOrdinal()throws Exception {
        UUID doc=fixture(),first=chunk(doc);call(delete(path(doc)+"/chunks/"+first).param("version",version(doc)+""),null,200);
        assertThat(jdbc.queryForObject("SELECT chunk_count FROM kb_generation WHERE document_id=?",Long.class,doc)).isZero();
        var created=call(post(path(doc)+"/chunks"),Map.of("version",version(doc),"content","Baking bread replacement"),201).path("chunks").get(0);
        assertThat(created.path("chunkIndex").asInt()).isEqualTo(1);assertThat(hits(doc,"bread").size()).isEqualTo(1);
        var page=call(get(path(doc)+"/chunks").param("size","1"),null,200);assertThat(page.path("total").asInt()).isEqualTo(1);
        assertThat(created.path("createdBy").asText()).isEqualTo("user-alice");
    }
    @Test void documentReenablePreservesIndividualDisabledChunksAndRestoresOnlyMissingEnabledVectors()throws Exception {
        UUID doc=fixture(),first=chunk(doc);
        UUID second=UUID.fromString(call(post(path(doc)+"/chunks"),Map.of("version",version(doc),"content","Database second evidence"),201).at("/chunks/0/id").asText());
        call(patch(path(doc)+"/chunks/"+first+"/enabled"),Map.of("version",version(doc),"enabled",false),200);
        assertThat(hits(doc,"database").size()).isEqualTo(1);
        call(patch(path(doc)+"/enabled"),Map.of("version",version(doc),"enabled",false),200);assertThat(hits(doc,"database").isEmpty()).isTrue();
        call(patch(path(doc)+"/chunks/"+first+"/enabled"),Map.of("version",version(doc),"enabled",true),409);
        jdbc.update("DELETE FROM kb_vector WHERE chunk_id IN (?,?)",first,second);
        int calls=model.requests.size();call(patch(path(doc)+"/enabled"),Map.of("version",version(doc),"enabled",true),200);
        assertThat(model.requests.size()).isEqualTo(calls+1);assertThat(hits(doc,"database").get(0).path("chunkId").asText()).isEqualTo(second.toString());
        assertThat(jdbc.queryForObject("SELECT enabled FROM kb_chunk WHERE id=?",Boolean.class,first)).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kb_vector WHERE chunk_id=?",Integer.class,first)).isZero();
        call(patch(path(doc)+"/chunks/"+first+"/enabled"),Map.of("version",version(doc),"enabled",true),200);
        assertThat(hits(doc,"database").size()).isEqualTo(2);
    }
    @Test void batchSelectorIsExplicitBoundedAndAtomicAcrossDocumentScopes()throws Exception {
        UUID kb=base(),doc=document(kb,"one.txt",true),other=document(kb,"two.txt",true);UUID id=chunk(doc),foreign=chunk(other);long before=version(doc);
        call(post(path(doc)+"/chunks/batch-enabled"),Map.of("version",before,"enabled",false,"chunkIds",List.of()),400);
        call(post(path(doc)+"/chunks/batch-enabled"),Map.of("version",before,"enabled",false,"chunkIds",List.of(id),"applyAll",true),400);
        call(post(path(doc)+"/chunks/batch-enabled"),Map.of("version",before,"enabled",false,"chunkIds",List.of(id,id)),400);
        call(post(path(doc)+"/chunks/batch-enabled"),Map.of("version",before,"enabled",false,"chunkIds",List.of(id,foreign,UUID.randomUUID())),400);
        call(post(path(doc)+"/chunks/batch-enabled"),Map.of("version",before,"enabled",false,"chunkIds",List.of(id,foreign)),404);
        assertThat(version(doc)).isEqualTo(before);
        call(post(path(doc)+"/chunks/batch-enabled"),Map.of("version",before,"enabled",false,"applyAll",true),200);
        assertThat(jdbc.queryForObject("SELECT enabled FROM kb_chunk WHERE id=?",Boolean.class,id)).isFalse();
        assertThat(jdbc.queryForObject("SELECT enabled FROM kb_chunk WHERE id=?",Boolean.class,foreign)).isTrue();
    }
    @Test void transactionFailureRollsBackBodyVectorCountsAndAuditTogether()throws Exception {
        UUID doc=fixture(),id=chunk(doc);String old=jdbc.queryForObject("SELECT content FROM kb_chunk WHERE id=?",String.class,id);
        jdbc.execute("CREATE FUNCTION fail_maintenance_vector() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'injected'; END $$");
        jdbc.execute("CREATE TRIGGER fail_vector BEFORE INSERT ON kb_vector FOR EACH ROW EXECUTE FUNCTION fail_maintenance_vector()");
        try {call(patch(path(doc)+"/chunks/"+id),Map.of("version",version(doc),"content","Baking bread"),503);}
        finally {jdbc.execute("DROP TRIGGER fail_vector ON kb_vector");jdbc.execute("DROP FUNCTION fail_maintenance_vector()");}
        assertThat(version(doc)).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT content FROM kb_chunk WHERE id=?",String.class,id)).isEqualTo(old);
        assertThat(hits(doc,"database").size()).isEqualTo(1);assertThat(hits(doc,"bread").isEmpty()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kb_content_audit WHERE action='CHUNK_EDIT'",Integer.class)).isZero();
    }
    @Test void modelFailureAndConcurrentIngestionLeaveOldBodyVisible()throws Exception {
        UUID doc=fixture(),id=chunk(doc);model.mode="WRONG_DIM";
        call(patch(path(doc)+"/chunks/"+id),Map.of("version",version(doc),"content","Bread"),502);assertThat(version(doc)).isEqualTo(1);
        model.mode="GOOD";model.beforeEmbed=()->{model.beforeEmbed=()->{};jobs.submit(actor(),doc,"concurrent-job-001",null);};
        call(patch(path(doc)+"/chunks/"+id),Map.of("version",version(doc),"content","Baking bread"),409);
        assertThat(hits(doc,"database").size()).isEqualTo(1);
        call(delete(path(doc)+"/chunks/"+id).param("version",version(doc)+""),null,409);
    }
    @Test void requiresExplicitVersionedConfirmationForReingestionIncludingManualDeletion()throws Exception {
        UUID doc=fixture();call(delete(path(doc)+"/chunks/"+chunk(doc)).param("version",version(doc)+""),null,200);
        call(post(path(doc)+"/ingestion-jobs"),Map.of("requestId","replace-manual-001"),409);
        call(post(path(doc)+"/ingestion-jobs"),Map.of("requestId","replace-manual-001","confirmOverwriteEdited",true),409);
        call(post(path(doc)+"/ingestion-jobs"),Map.of("requestId","replace-manual-001","confirmOverwriteEdited",true,"expectedDocumentVersion",1),409);
        var body=Map.of("requestId","replace-manual-001","confirmOverwriteEdited",true,"expectedDocumentVersion",version(doc));
        UUID job=UUID.fromString(call(post(path(doc)+"/ingestion-jobs"),body,202).path("id").asText());
        assertThat(call(post(path(doc)+"/ingestion-jobs"),body,200).path("id").asText()).isEqualTo(job.toString());
        call(post(path(doc)+"/ingestion-jobs"),Map.of("requestId","replace-manual-001"),409);
        var claim=jobs.claimNext();kernel.execute(claim);
        assertThat(jobs.getOwnedJob(actor(),job).status()).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT has_manual_changes FROM kb_document WHERE id=?",Boolean.class,doc)).isFalse();
        assertThat(hits(doc,"database").size()).isEqualTo(1);
    }
    @Test void deletedDocumentFencesLateWorkerAndDurablyCleansEveryGenerationAndObject()throws Exception {
        UUID doc=fixture();String key=jdbc.queryForObject("SELECT object_key FROM kb_document WHERE id=?",String.class,doc);
        kernel.ingest(actor(),doc);jobs.submit(actor(),doc,"late-delete-job-001",null);var late=jobs.claimNext();
        UUID op=UUID.fromString(call(delete(path(doc)).param("version",version(doc)+""),null,202).path("id").asText());
        assertThat(jdbc.queryForObject("SELECT status FROM kb_ingestion_job WHERE id=?",String.class,late.jobId())).isEqualTo("CANCELLED");
        assertThat(call(delete(path(doc)).param("version","1"),null,202).path("id").asText()).isEqualTo(op.toString());
        call(get(path(doc)),null,404);call(get(path(doc)+"/file"),null,404);assertThat(hits(doc,"database").isEmpty()).isTrue();
        assertThatThrownBy(()->kernel.execute(late)).isInstanceOf(KnowledgeBaseException.class);
        storage.failDelete=true;assertThat(cleanup.runOnce()).isTrue();var failed=cleanup.get(actor(),op);
        assertThat(failed.status()).isEqualTo("PENDING");assertThat(failed.stage()).isEqualTo("OBJECT");assertThat(storage.keys).contains(key);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kb_chunk WHERE document_id=?",Integer.class,doc)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kb_generation WHERE document_id=?",Integer.class,doc)).isZero();
        storage.failDelete=false;jdbc.update("UPDATE kb_document_cleanup SET next_run_at=clock_timestamp() WHERE id=?",op);cleanup.runOnce();
        assertThat(cleanup.get(actor(),op).status()).isEqualTo("SUCCEEDED");assertThat(storage.keys).doesNotContain(key);
    }
    @Test void cleanupFailureIsBoundedAndHasAuthorizedManualRetry()throws Exception {
        UUID doc=fixture();UUID op=UUID.fromString(call(delete(path(doc)).param("version",version(doc)+""),null,202).path("id").asText());
        jdbc.update("UPDATE kb_document_cleanup SET max_attempts=1 WHERE id=?",op);storage.failDelete=true;cleanup.runOnce();
        assertThat(cleanup.get(actor(),op).status()).isEqualTo("FAILED");assertThat(cleanup.runOnce()).isFalse();
        call(post("/api/document-cleanups/"+op+"/retry"),null,200);storage.failDelete=false;cleanup.runOnce();assertThat(cleanup.get(actor(),op).status()).isEqualTo("SUCCEEDED");
    }
    @Test void expiredCleanupClaimsRecoverAndExhaustedClaimsBecomeFailed()throws Exception {
        UUID doc=fixture();UUID op=UUID.fromString(call(delete(path(doc)).param("version",version(doc)+""),null,202).path("id").asText());
        jdbc.update("UPDATE kb_document_cleanup SET status='RUNNING',attempts=1,claim_token=8,lease_owner='dead-process',lease_until=clock_timestamp()-interval '1 second',next_run_at=NULL WHERE id=?",op);
        cleanup.runOnce();assertThat(cleanup.get(actor(),op).status()).isEqualTo("SUCCEEDED");assertThat(cleanup.get(actor(),op).attempts()).isEqualTo(2);
        UUID other=document(base(),"other.txt",false);UUID second=UUID.fromString(call(delete(path(other)).param("version",version(other)+""),null,202).path("id").asText());
        jdbc.update("UPDATE kb_document_cleanup SET status='RUNNING',attempts=max_attempts,lease_owner='dead',lease_until=clock_timestamp()-interval '1 second' WHERE id=?",second);
        cleanup.runOnce();assertThat(cleanup.get(actor(),second).status()).isEqualTo("FAILED");
    }
    @Test void disabledDocumentCancelsRunningJobAndDoesNotRestoreItOnEnable()throws Exception {
        UUID doc=fixture();jobs.submit(actor(),doc,"disable-running-001",null);var late=jobs.claimNext();
        call(patch(path(doc)+"/enabled"),Map.of("version",version(doc),"enabled",false),200);
        assertThatThrownBy(()->kernel.execute(late)).isInstanceOf(KnowledgeBaseException.class);
        call(patch(path(doc)+"/enabled"),Map.of("version",version(doc),"enabled",true),200);
        assertThat(jobs.getOwnedJob(actor(),late.jobId()).status()).isEqualTo("CANCELLED");assertThat(hits(doc,"database").size()).isEqualTo(1);
    }
    @Test void rejectsUnauthorizedForeignOrStaleContentAndUnknownFieldsBeforeModelCalls()throws Exception {
        UUID doc=fixture(),id=chunk(doc);int count=model.requests.size();
        for(String user:List.of("bob","carol")) {
            mvc.perform(get(path(doc)+"/chunks").with(httpBasic(user,user+"-local-test-password"))).andExpect(status().isNotFound());
            mvc.perform(patch(path(doc)+"/chunks/"+id).with(httpBasic(user,user+"-local-test-password")).contentType("application/json").content("{\"version\":1,\"content\":\"bread\"}")).andExpect(status().isNotFound());
        }
        mvc.perform(get(path(doc)+"/chunks")).andExpect(status().isUnauthorized());
        call(patch(path(doc)+"/chunks/"+id),Map.of("version",99,"content","Bread"),409);
        call(post(path(doc)+"/chunks"),Map.of("version",1,"content"," \n"),400);
        call(post(path(doc)+"/chunks"),Map.of("version",1,"content","x".repeat(8193)),400);
        call(post(path(doc)+"/chunks"),Map.of("version",1,"content","Bread","tenantId","other"),400);
        call(patch(path(doc)+"/chunks/"+UUID.randomUUID()),Map.of("version",1,"content","Bread"),404);
        assertThat(model.requests.size()).isEqualTo(count);
        UUID pending=document(base(),"pending.txt",false);call(post(path(pending)+"/chunks"),Map.of("version",1,"content","Bread"),409);
    }
    @Test void lateEmbeddingCannotPublishAfterDocumentDeletion()throws Exception {
        UUID doc=fixture();jobs.submit(actor(),doc,"delete-during-http-001",null);var claim=jobs.claimNext();
        model.beforeEmbed=()->{model.beforeEmbed=()->{};cleanup.delete(actor(),doc,version(doc));};
        assertThatThrownBy(()->kernel.execute(claim)).isInstanceOf(KnowledgeBaseException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM kb_ingestion_job WHERE id=?",String.class,claim.jobId())).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT active_generation IS NULL FROM kb_document WHERE id=?",Boolean.class,doc)).isTrue();
        assertThat(hits(doc,"database").isEmpty()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kb_generation WHERE document_id=?",Integer.class,doc)).isEqualTo(1);
    }
    @Test void concurrentManualRequestsHaveOneVersionWinner()throws Exception {
        UUID doc=fixture(),id=chunk(doc);var entered=new CountDownLatch(2);var release=new CountDownLatch(1);
        model.beforeEmbed=()->{entered.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Model barrier timeout");}catch(InterruptedException e){throw new IllegalStateException(e);}};
        var pool=Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> results=new ArrayList<>();
            for(String body:List.of("Baking bread first","Database second"))results.add(pool.submit(()->mvc.perform(patch(path(doc)+"/chunks/"+id)
                    .with(httpBasic("alice","alice-local-test-password")).contentType("application/json").content(json.writeValueAsString(Map.of("version",1,"content",body)))).andReturn().getResponse().getStatus()));
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();release.countDown();
            assertThat(List.of(results.get(0).get(10,TimeUnit.SECONDS),results.get(1).get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
            assertThat(version(doc)).isEqualTo(2);assertThat(jdbc.queryForObject("SELECT count(*) FROM kb_content_audit WHERE document_id=? AND action='CHUNK_EDIT'",Integer.class,doc)).isEqualTo(1);
        } finally {release.countDown();model.beforeEmbed=()->{};pool.shutdownNow();}
    }
    @Test void waitingForParentLockRechecksVersionInsteadOfReusingAnEarlierOrmRead()throws Exception {
        UUID doc=fixture(),id=chunk(doc),kb=jdbc.queryForObject("SELECT kb_id FROM kb_document WHERE id=?",UUID.class,doc);
        var pool=Executors.newFixedThreadPool(2);
        try(var connection=Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            connection.setAutoCommit(false);
            try(var statement=connection.prepareStatement("SELECT id FROM kb_knowledge_base WHERE id=? FOR UPDATE")){statement.setObject(1,kb);statement.executeQuery().close();}
            var edit=pool.submit(()->mvc.perform(patch(path(doc)+"/chunks/"+id).with(httpBasic("alice","alice-local-test-password"))
                    .contentType("application/json").content(json.writeValueAsString(Map.of("version",1,"content","Baking bread waiting")))).andReturn().getResponse().getStatus());
            var submit=pool.submit(()->mvc.perform(post(path(doc)+"/ingestion-jobs").with(httpBasic("alice","alice-local-test-password"))
                    .contentType("application/json").content("{\"requestId\":\"waiting-submit-001\"}")).andReturn().getResponse().getStatus());
            await(()->jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%kb_knowledge_base%'",Integer.class)>=2,5);
            // Change the version while both requests wait. Neither may publish from its earlier snapshot.
            try(var statement=connection.prepareStatement("UPDATE kb_document SET version=version+1,has_manual_changes=TRUE,manual_revision=1 WHERE id=?")){statement.setObject(1,doc);statement.executeUpdate();}
            connection.commit();assertThat(edit.get(10,TimeUnit.SECONDS)).isEqualTo(409);assertThat(submit.get(10,TimeUnit.SECONDS)).isEqualTo(409);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM kb_ingestion_job WHERE document_id=?",Integer.class,doc)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT edited FROM kb_chunk WHERE id=?",Boolean.class,id)).isFalse();
        }finally{pool.shutdownNow();}
    }
    @Test void databaseCleanupFailureRollsBackAndBlocksDeletingTheParentUntilRetryCompletes()throws Exception {
        UUID doc=fixture(),kb=jdbc.queryForObject("SELECT kb_id FROM kb_document WHERE id=?",UUID.class,doc);
        UUID op=UUID.fromString(call(delete(path(doc)).param("version",version(doc)+""),null,202).path("id").asText());
        jdbc.execute("CREATE FUNCTION fail_doc_cleanup() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'injected'; END $$");
        jdbc.execute("CREATE TRIGGER fail_doc_cleanup BEFORE DELETE ON kb_chunk FOR EACH ROW EXECUTE FUNCTION fail_doc_cleanup()");
        try {cleanup.runOnce();}finally{jdbc.execute("DROP TRIGGER fail_doc_cleanup ON kb_chunk");jdbc.execute("DROP FUNCTION fail_doc_cleanup()");}
        assertThat(cleanup.get(actor(),op).stage()).isEqualTo("DATABASE");assertThat(cleanup.get(actor(),op).status()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kb_chunk WHERE document_id=?",Integer.class,doc)).isEqualTo(1);
        call(delete("/api/kbs/"+kb).param("version","1"),null,409);
        jdbc.update("UPDATE kb_document_cleanup SET next_run_at=clock_timestamp() WHERE id=?",op);cleanup.runOnce();
        call(delete("/api/kbs/"+kb).param("version","1"),null,200);
        call(get("/api/document-cleanups/"+op),null,200); // Receipt remains owned after parent tombstone.
    }
    @Test void versionedBucketDoesNotSilentlyReportPhysicalDeletion()throws Exception {
        String bucket="kb-versioned-"+UUID.randomUUID();storage.client.createBucket(b->b.bucket(bucket));
        storage.client.putBucketVersioning(b->b.bucket(bucket).versioningConfiguration(v->v.status(software.amazon.awssdk.services.s3.model.BucketVersioningStatus.ENABLED)));
        var put=storage.client.putObject(b->b.bucket(bucket).key("source"),software.amazon.awssdk.core.sync.RequestBody.fromString("original"));
        try {
            var adapter=new com.lzj.rag.backend.storage.S3ObjectStorageClient(storage.client,bucket);
            assertThatThrownBy(()->adapter.delete("source")).isInstanceOfSatisfying(KnowledgeBaseException.class,e->assertThat(e.code()).isEqualTo("KB_STORAGE_VERSIONED_BUCKET"));
            assertThat(storage.client.headObject(b->b.bucket(bucket).key("source")).contentLength()).isEqualTo(8L);
        }finally{storage.client.deleteObject(b->b.bucket(bucket).key("source").versionId(put.versionId()));storage.client.deleteBucket(b->b.bucket(bucket));}
    }
    @Test void realProcessRestartResumesPersistedObjectCleanupAndPreservesChunkToggle()throws Exception {
        UUID keep=fixture(),id=chunk(keep);call(patch(path(keep)+"/chunks/"+id+"/enabled"),Map.of("version",version(keep),"enabled",false),200);
        call(patch(path(keep)+"/enabled"),Map.of("version",version(keep),"enabled",false),200);
        UUID doc=document(base(),"restart.txt",true);UUID op=UUID.fromString(call(delete(path(doc)).param("version",version(doc)+""),null,202).path("id").asText());
        Process first=null,second=null;String key=jdbc.queryForObject("SELECT object_key FROM kb_document WHERE id=?",String.class,doc);
        var directory=java.nio.file.Path.of("target/knowledge-base").toAbsolutePath();java.nio.file.Files.createDirectories(directory);
        // First real process commits DATABASE, then fails OBJECT due to a missing bucket. Stop it and repair config for restart.
        try {
            first=startCleanupProcess("before-restart","missing-bucket");
            await(()->cleanup.get(actor(),op).attempts()>=1 && cleanup.get(actor(),op).status().equals("PENDING"),45);
            assertThat(cleanup.get(actor(),op).stage()).isEqualTo("OBJECT");first.destroyForcibly();assertThat(first.waitFor(10,TimeUnit.SECONDS)).isTrue();
            jdbc.update("UPDATE kb_document_cleanup SET next_run_at=clock_timestamp() WHERE id=?",op);
            second=startCleanupProcess("after-restart","kb-maintenance-test");await(()->cleanup.get(actor(),op).status().equals("SUCCEEDED"),45);
            assertThat(cleanup.get(actor(),op).attempts()).isGreaterThanOrEqualTo(2);
            assertThatThrownBy(()->storage.open(key)).isInstanceOf(KnowledgeBaseException.class);storage.keys.remove(key);
            call(patch(path(keep)+"/enabled"),Map.of("version",version(keep),"enabled",true),200);
            assertThat(hits(keep,"database").isEmpty()).isTrue();assertThat(jdbc.queryForObject("SELECT enabled FROM kb_chunk WHERE id=?",Boolean.class,id)).isFalse();
            java.nio.file.Files.writeString(directory.resolve("maintenance-restart-evidence.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                    "firstPid",first.pid(),"secondPid",second.pid(),"cleanup",cleanup.get(actor(),op),"disabledChunk",id,"vectorSource","Local HTTP fixture; two real Java processes, PostgreSQL and MinIO")));
        }finally {for(var process:new Process[]{first,second})if(process!=null && process.isAlive()){process.destroyForcibly();process.waitFor(10,TimeUnit.SECONDS);}}
    }
    private Process startCleanupProcess(String label,String bucket)throws Exception {
        var directory=java.nio.file.Path.of("target/knowledge-base").toAbsolutePath();var args=directory.resolve("maintenance-"+label+".args");
        String classpath=System.getProperty("java.class.path").replace("\\","\\\\");
        java.nio.file.Files.writeString(args,"-Xmx256m\n-Dfile.encoding=UTF-8\n-cp\n\""+classpath+"\"\ncom.lzj.rag.backend.BackendApplication\n--server.port=0\n--kb.worker.enabled=true\n--kb.worker.document-cleanup-poll-delay=200\n--kb.worker.backoff=30s\n");
        var builder=new ProcessBuilder(java.nio.file.Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString(),"@"+args);
        var env=builder.environment();env.put("SPRING_PROFILES_ACTIVE","test");env.put("SPRING_DATASOURCE_URL",DATABASE.getJdbcUrl());env.put("SPRING_DATASOURCE_USERNAME",DATABASE.getUsername());env.put("SPRING_DATASOURCE_PASSWORD",DATABASE.getPassword());
        env.put("KB_STORAGE_ENABLED","true");env.put("KB_S3_BUCKET",bucket);env.put("KB_S3_ENDPOINT","http://"+MINIO.getHost()+":"+MINIO.getMappedPort(9000));env.put("KB_S3_PATH_STYLE","true");
        env.put("AWS_ACCESS_KEY_ID","kb-test-access");env.put("AWS_SECRET_ACCESS_KEY","kb-test-secret-password");env.put("KB_AI_BASE_URL",model.url());
        return builder.redirectErrorStream(true).redirectOutput(directory.resolve("maintenance-"+label+".log").toFile()).start();
    }
    private void await(java.util.function.BooleanSupplier condition,int seconds)throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);while(System.nanoTime()<end){if(condition.getAsBoolean())return;Thread.sleep(50);}
        assertThat(condition.getAsBoolean()).as("Condition within %s seconds; inspect maintenance process logs",seconds).isTrue();
    }
}
