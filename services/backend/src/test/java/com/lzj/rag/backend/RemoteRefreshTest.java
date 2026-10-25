package com.lzj.rag.backend;

import com.fasterxml.jackson.databind.*;
import com.lzj.rag.backend.document.*;
import com.lzj.rag.backend.ingestion.*;
import com.lzj.rag.backend.knowledgebase.KnowledgeBaseException;
import com.lzj.rag.backend.model.EmbeddingStub;
import com.lzj.rag.backend.refresh.*;
import com.lzj.rag.backend.security.KnowledgeBasePrincipal;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
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
import static com.lzj.rag.backend.refresh.RemoteTypes.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"kb.storage.enabled=true","kb.storage.bucket=kb-remote-test","kb.remote.enabled=true","kb.remote.allowed-hosts=127.0.0.1,169.254.169.254","kb.remote.trusted-private-hosts=127.0.0.1","kb.remote.allow-http=true","kb.remote.download-timeout=500ms","kb.remote.read-timeout=500ms","kb.remote.max-download-bytes=2048"})
@AutoConfigureMockMvc @ActiveProfiles("test") @Import(DocumentUploadTest.StorageTestConfiguration.class)
@Testcontainers(disabledWithoutDocker=true)
class RemoteRefreshTest {
    static EmbeddingStub model;static RemoteSourceStub origin;
    static synchronized String modelUrl(){if(model==null)model=new EmbeddingStub();return model.url();}
    static synchronized RemoteSourceStub origin(){if(origin==null)origin=new RemoteSourceStub();return origin;}
    @AfterAll static void stop(){if(model!=null)model.close();if(origin!=null)origin.close();}
    @Container static final PostgreSQLContainer<?> DATABASE=new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:0.8.7-pg17-bookworm").asCompatibleSubstituteFor("postgres"));
    @Container static final GenericContainer<?> MINIO=new GenericContainer<>(DockerImageName.parse("minio/minio:RELEASE.2025-09-07T16-13-09Z"))
            .withEnv("MINIO_ROOT_USER","kb-test-access").withEnv("MINIO_ROOT_PASSWORD","kb-test-secret-password").withCommand("server","/data").withExposedPorts(9000).waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",DATABASE::getJdbcUrl);p.add("spring.datasource.username",DATABASE::getUsername);p.add("spring.datasource.password",DATABASE::getPassword);
        p.add("kb.storage.endpoint",()->"http://"+MINIO.getHost()+":"+MINIO.getMappedPort(9000));p.add("kb.storage.path-style-access",()->true);
        p.add("kb.storage.temp-directory",()->Path.of("target/knowledge-base/remote-spool").toAbsolutePath().toString());p.add("kb.ai.base-url",RemoteRefreshTest::modelUrl);
        p.add("kb.remote.allowed-ports",()->origin().port()+",80");
    }
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired JdbcTemplate jdbc;@Autowired UserDetailsService users;
    @Autowired RefreshService refresh;@Autowired IngestionJobService jobs;@Autowired IngestionKernel kernel;@Autowired DocumentService documents;
    @Autowired RemoteDownloader downloader;@Autowired RemoteObjectCleanup gc;@Autowired DocumentCleanupService deletion;
    @Autowired DocumentUploadTest.FaultStorage storage;
    @BeforeEach void clear(){model.reset();origin().reset();storage.reset();TestDatabaseCleanup.clear(jdbc);}
    private KnowledgeBasePrincipal actor(){return (KnowledgeBasePrincipal)users.loadUserByUsername("alice");}
    private JsonNode call(MockHttpServletRequestBuilder request,Object body,int status)throws Exception{return call(request,body,status,"alice");}
    private JsonNode call(MockHttpServletRequestBuilder request,Object body,int status,String user)throws Exception {
        if(body!=null)request.contentType("application/json").content(json.writeValueAsBytes(body));
        var response=mvc.perform(request.with(httpBasic(user,user+"-local-test-password"))).andExpect(status().is(status)).andReturn().getResponse();return response.getContentAsByteArray().length==0?json.nullNode():json.readTree(response.getContentAsByteArray()).path("data");
    }
    private UUID base()throws Exception{return UUID.fromString(call(post("/api/kbs"),Map.of("name","remote"),201).path("id").asText());}
    private JsonNode importDoc(UUID kb,String key,boolean ingest,int status)throws Exception{return call(post("/api/kbs/"+kb+"/documents/import-url").header("Idempotency-Key",key),Map.of("url",origin().url(),"filename","remote.md","ingest",ingest),status);}
    private UUID fixture()throws Exception {var imported=importDoc(base(),UUID.randomUUID().toString(),true,202);UUID doc=UUID.fromString(imported.at("/document/id").asText());kernel.execute(jobs.claimNext());return doc;}
    private String path(UUID doc){return "/api/documents/"+doc;}
    private UUID submit(UUID doc)throws Exception{return UUID.fromString(call(post(path(doc)+"/refresh"),Map.of("requestId",UUID.randomUUID().toString()),202).path("id").asText());}
    private String runStatus(UUID id){return jdbc.queryForObject("SELECT status FROM kb_refresh_run WHERE id=?",String.class,id);}
    private Map<String,Object> doc(UUID id){return jdbc.queryForMap("SELECT * FROM kb_document WHERE id=?",id);}
    private void changed(){origin().body="# Cooking\n\nBaking bread with flour.";origin().etag="\"v2\"";}
    private void executeRefresh(){var claim=refresh.claimNext();assertThat(claim).isNotNull();refresh.execute(claim);}
    @Test void importReusesUploadAndDurableJobsAndIdempotencyWithoutAnotherDownload()throws Exception {
        UUID kb=base();String key=UUID.randomUUID().toString();var imported=importDoc(kb,key,true,202);UUID document=UUID.fromString(imported.at("/document/id").asText());
        assertThat(imported.at("/document/sourceType").asText()).isEqualTo("URL");assertThat(model.requests).isEmpty();assertThat(imported.at("/document/processStatus").asText()).isEqualTo("PROCESSING");
        int calls=origin().calls.get();var replay=importDoc(kb,key,true,200);assertThat(replay.path("jobId")).isEqualTo(imported.path("jobId"));assertThat(origin().calls.get()).isEqualTo(calls);
        assertThat(jdbc.queryForObject("SELECT last_content_hash FROM kb_refresh_schedule WHERE document_id=?",String.class,document)).isNull();
        kernel.execute(jobs.claimNext());assertThat(jdbc.queryForObject("SELECT last_etag FROM kb_refresh_schedule WHERE document_id=?",String.class,document)).isEqualTo("\"v1\"");
        mvc.perform(get(path(document)+"/file").with(httpBasic("alice","alice-local-test-password"))).andExpect(content().string(origin().body));
        call(get(path(document)+"/refresh-runs"),null,404,"bob");call(post(path(document)+"/refresh"),Map.of("requestId","foreign-refresh-001"),404,"carol");
        mvc.perform(get(path(document)+"/refresh-schedule")).andExpect(status().isUnauthorized());
    }
    @Test void saveOnlyRemainsPendingAndHasNoSuccessfulBaselineBeforeItsFirstActivation()throws Exception {
        var imported=importDoc(base(),UUID.randomUUID().toString(),false,201);UUID doc=UUID.fromString(imported.at("/document/id").asText());assertThat(imported.path("jobId").isNull()).isTrue();
        assertThat(doc(doc).get("process_status")).isEqualTo("PENDING");assertThat(model.requests).isEmpty();
        submit(doc);executeRefresh();assertThat(origin().lastIfNoneMatch).isNull();kernel.execute(jobs.claimNext());assertThat(doc(doc).get("active_generation")).isEqualTo(1L);
    }
    @Test void conditional304AndSameHashWithRotatedHeadersSkipEveryParserAndModelCall()throws Exception {
        UUID doc=fixture();var before=doc(doc);int embeddings=model.requests.size();UUID unchanged=submit(doc);executeRefresh();
        assertThat(runStatus(unchanged)).isEqualTo("SKIPPED");assertThat(origin().lastIfNoneMatch).isEqualTo("\"v1\"");assertThat(origin().lastIfModifiedSince).isNotBlank();
        origin().mode="NO_CONDITIONAL";origin().etag="\"same-bytes-new-header\"";UUID same=submit(doc);executeRefresh();
        assertThat(runStatus(same)).isEqualTo("SKIPPED");assertThat(jdbc.queryForObject("SELECT reason FROM kb_refresh_run WHERE id=?",String.class,same)).isEqualTo("CONTENT_HASH_UNCHANGED");
        assertThat(jdbc.queryForObject("SELECT last_etag FROM kb_refresh_schedule WHERE document_id=?",String.class,doc)).isEqualTo(origin().etag);
        assertThat(doc(doc).get("object_key")).isEqualTo(before.get("object_key"));assertThat(model.requests).hasSize(embeddings);
    }
    @Test void changedSourcePublishesFileGenerationAndBaselineTogetherAndOldSourceIsReclaimed()throws Exception {
        UUID doc=fixture();var before=doc(doc);changed();UUID run=submit(doc);executeRefresh();assertThat(runStatus(run)).isEqualTo("WAITING_INGESTION");
        assertThat(doc(doc).get("content_hash")).isEqualTo(before.get("content_hash"));assertThat(jdbc.queryForObject("SELECT last_etag FROM kb_refresh_schedule WHERE document_id=?",String.class,doc)).isEqualTo("\"v1\"");
        mvc.perform(get(path(doc)+"/file").with(httpBasic("alice","alice-local-test-password"))).andExpect(content().string("# Database\n\nPostgreSQL connection backup."));
        kernel.execute(jobs.claimNext());assertThat(runStatus(run)).isEqualTo("SUCCESS");assertThat(doc(doc).get("active_generation")).isEqualTo(2L);assertThat(doc(doc).get("version")).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT last_etag FROM kb_refresh_schedule WHERE document_id=?",String.class,doc)).isEqualTo("\"v2\"");
        mvc.perform(get(path(doc)+"/file").with(httpBasic("alice","alice-local-test-password"))).andExpect(content().string(origin().body));
        var hits=call(post("/api/knowledge/search"),Map.of("query","bread","kbIds",List.of(before.get("kb_id"))),200);assertThat(hits).hasSize(1);assertThat(hits.get(0).path("generation").asLong()).isEqualTo(2);assertThat(hits.get(0).path("content").asText()).contains("Baking");
        jdbc.update("UPDATE kb_remote_object_cleanup SET next_run_at=clock_timestamp() WHERE status='PENDING'");assertThat(gc.runOnce()).isTrue();assertThatThrownBy(()->storage.open(before.get("object_key").toString())).isInstanceOf(KnowledgeBaseException.class);storage.keys.remove(before.get("object_key"));
        Files.createDirectories(Path.of("target/knowledge-base"));Files.writeString(Path.of("target/knowledge-base/refresh-example.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("source","Local controlled HTTP origin; actual PostgreSQL/MinIO; embedding HTTP fixture","run",call(get(path(doc)+"/refresh-runs"),null,200),"schedule",call(get(path(doc)+"/refresh-schedule"),null,200),"search",hits)));
    }
    @Test void ingestionFailureNeverMovesValidatorsAndTheSameRemoteChangeIsFetchedAgain()throws Exception {
        UUID doc=fixture();var before=doc(doc);changed();UUID run=submit(doc);executeRefresh();model.mode="WRONG_DIM";
        assertThatThrownBy(()->kernel.execute(jobs.claimNext())).isInstanceOf(KnowledgeBaseException.class);refresh.reconcile();assertThat(runStatus(run)).isEqualTo("FAILED");
        assertThat(doc(doc).get("object_key")).isEqualTo(before.get("object_key"));assertThat(doc(doc).get("active_generation")).isEqualTo(1L);assertThat(jdbc.queryForObject("SELECT last_etag FROM kb_refresh_schedule WHERE document_id=?",String.class,doc)).isEqualTo("\"v1\"");
        model.reset();UUID next=submit(doc);executeRefresh();assertThat(origin().lastIfNoneMatch).isEqualTo("\"v1\"");kernel.execute(jobs.claimNext());assertThat(runStatus(next)).isEqualTo("SUCCESS");
    }
    @Test void parserFailureKeepsOldKnowledgeAndCandidateCleanupFailureIsDurablyRetried()throws Exception {
        UUID doc=fixture();origin().body="# Empty heading only";origin().etag="\"empty\"";UUID run=submit(doc);executeRefresh();
        assertThatThrownBy(()->kernel.execute(jobs.claimNext())).isInstanceOf(KnowledgeBaseException.class);refresh.reconcile();assertThat(runStatus(run)).isEqualTo("FAILED");
        storage.failDelete=true;jdbc.update("UPDATE kb_remote_object_cleanup SET next_run_at=clock_timestamp() WHERE status='PENDING'");assertThat(gc.runOnce()).isTrue();
        assertThat(jdbc.queryForObject("SELECT attempts FROM kb_remote_object_cleanup WHERE run_id=?",Integer.class,run)).isEqualTo(1);assertThat(doc(doc).get("active_generation")).isEqualTo(1L);
        storage.failDelete=false;jdbc.update("UPDATE kb_remote_object_cleanup SET next_run_at=clock_timestamp() WHERE status='PENDING'");gc.runOnce();assertThat(jdbc.queryForObject("SELECT status FROM kb_remote_object_cleanup WHERE run_id=?",String.class,run)).isEqualTo("SUCCEEDED");
    }
    @Test void duplicateSubmissionAndConcurrentClaimsHaveOneWinnerAndManualIngestionCannotRace()throws Exception {
        UUID doc=fixture();String key="manual-refresh-idempotent-001";var first=call(post(path(doc)+"/refresh"),Map.of("requestId",key),202);assertThat(call(post(path(doc)+"/refresh"),Map.of("requestId",key),200).path("id")).isEqualTo(first.path("id"));
        call(post(path(doc)+"/refresh"),Map.of("requestId","another-refresh-key"),409);call(post(path(doc)+"/ingestion-jobs"),Map.of("requestId","manual-job-race-001"),409);
        var pool=Executors.newFixedThreadPool(8);try{var futures=new ArrayList<Future<RefreshService.Claim>>();for(int i=0;i<8;i++)futures.add(pool.submit(refresh::claimNext));int winners=0;for(var future:futures)if(future.get(10,TimeUnit.SECONDS)!=null)winners++;assertThat(winners).isEqualTo(1);}finally{pool.shutdownNow();}
    }
    @Test void lostLeaseRejectsLateModelResultAndExpiredClaimRecoversWithANewJob()throws Exception {
        UUID doc=fixture();changed();UUID run=submit(doc);executeRefresh();var old=jobs.claimNext();
        jdbc.update("UPDATE kb_refresh_schedule SET lease_until=clock_timestamp()-interval '1 second' WHERE document_id=?",doc);jdbc.update("UPDATE kb_refresh_run SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?",run);
        assertThatThrownBy(()->kernel.execute(old)).isInstanceOf(KnowledgeBaseException.class);assertThat(doc(doc).get("active_generation")).isEqualTo(1L);
        assertThat(refresh.claimNext()).isNull();jdbc.update("UPDATE kb_refresh_run SET next_run_at=clock_timestamp() WHERE id=?",run);executeRefresh();var replacement=jobs.claimNext();kernel.execute(replacement);
        assertThat(runStatus(run)).isEqualTo("SUCCESS");assertThat(replacement.jobId()).isNotEqualTo(old.jobId());assertThat(jdbc.queryForObject("SELECT attempts FROM kb_refresh_run WHERE id=?",Integer.class,run)).isEqualTo(2);
        assertThatThrownBy(()->kernel.execute(old)).isInstanceOf(KnowledgeBaseException.class);
    }
    @Test void disableAndDeleteFenceDownloadsAndStopSchedules()throws Exception {
        UUID doc=fixture();var schedule=call(get(path(doc)+"/refresh-schedule"),null,200);call(put(path(doc)+"/refresh-schedule"),Map.of("version",schedule.path("version").asLong(),"enabled",true,"cron","0 * * * * *","timezone","Asia/Shanghai"),200);
        changed();UUID run=submit(doc);var claim=refresh.claimNext();origin().beforeResponse=()->{origin().beforeResponse=()->{};try{call(patch(path(doc)+"/enabled"),Map.of("version",1,"enabled",false),200);}catch(Exception e){throw new IllegalStateException(e);}};
        refresh.execute(claim);assertThat(runStatus(run)).isEqualTo("SKIPPED");assertThat(call(get(path(doc)+"/refresh-schedule"),null,200).path("enabled").asBoolean()).isFalse();assertThat(doc(doc).get("active_generation")).isEqualTo(1L);
        call(patch(path(doc)+"/enabled"),Map.of("version",2,"enabled",true),200);run=submit(doc);executeRefresh();var job=jobs.claimNext();deletion.delete(actor(),doc,3);assertThatThrownBy(()->kernel.execute(job)).isInstanceOf(KnowledgeBaseException.class);assertThat(runStatus(run)).isEqualTo("SKIPPED");assertThat(doc(doc).get("active_generation")).isNull();
    }
    @Test void schedulesValidateTimezoneVersionAndCronAndDueTicksDoNotDuplicateRuns()throws Exception {
        UUID doc=fixture();call(put(path(doc)+"/refresh-schedule"),Map.of("version",1,"enabled",true,"cron","bad","timezone","Asia/Shanghai"),400);
        call(put(path(doc)+"/refresh-schedule"),Map.of("version",1,"enabled",true,"cron","0 * * * * *","timezone","Mars/Test"),400);
        var configured=call(put(path(doc)+"/refresh-schedule"),Map.of("version",1,"enabled",true,"cron","0 0 9 * * *","timezone","Asia/Shanghai"),200);assertThat(configured.path("nextRunAt").asText()).endsWith("Z");
        call(put(path(doc)+"/refresh-schedule"),Map.of("version",1,"enabled",false,"cron","0 * * * * *","timezone","UTC"),409);
        jdbc.update("UPDATE kb_refresh_schedule SET next_run_at=clock_timestamp()-interval '1 second' WHERE document_id=?",doc);refresh.enqueueDue();refresh.enqueueDue();assertThat(jdbc.queryForObject("SELECT count(*) FROM kb_refresh_run WHERE document_id=?",Integer.class,doc)).isEqualTo(1);executeRefresh();assertThat(call(get(path(doc)+"/refresh-schedule"),null,200).path("nextRunAt").asText()).isNotBlank();
        assertThat(RefreshState.next("0 0 9 * * *","Asia/Shanghai",OffsetDateTime.parse("2026-10-24T00:00:00Z"))).isEqualTo(OffsetDateTime.parse("2026-10-24T01:00:00Z"));
    }
    @Test void manualChangesNeedExplicitConfirmationAndAutomaticRefreshSkipsThem()throws Exception {
        UUID doc=fixture();jdbc.update("UPDATE kb_document SET has_manual_changes=TRUE,version=version+1 WHERE id=?",doc);
        call(post(path(doc)+"/refresh"),Map.of("requestId","manual-overwrite-001"),409);call(post(path(doc)+"/refresh"),Map.of("requestId","manual-overwrite-002","confirmOverwriteEdited",true,"expectedDocumentVersion",1),409);
        call(put(path(doc)+"/refresh-schedule"),Map.of("version",1,"enabled",true,"cron","0 * * * * *","timezone","UTC"),200);jdbc.update("UPDATE kb_refresh_schedule SET next_run_at=clock_timestamp() WHERE document_id=?",doc);refresh.enqueueDue();assertThat(jdbc.queryForObject("SELECT reason FROM kb_refresh_run WHERE document_id=?",String.class,doc)).isEqualTo("MANUAL_CHANGES_REQUIRE_CONFIRMATION");
        changed();call(post(path(doc)+"/refresh"),Map.of("requestId","manual-overwrite-003","confirmOverwriteEdited",true,"expectedDocumentVersion",2),202);executeRefresh();kernel.execute(jobs.claimNext());assertThat(doc(doc).get("has_manual_changes")).isEqualTo(false);
    }
    @Test void downloadsRejectUnsafeAddressesRedirectsEmptyOversizeAndTimeoutWithoutUnboundedBuffers()throws Exception {
        UUID kb=base();for(String mode:List.of("REDIRECT","LOOP","EMPTY","LENGTH","SLOW")) {origin().mode=mode;int expected=switch(mode){case "REDIRECT"->400;case "LOOP"->422;case "EMPTY"->400;case "LENGTH"->413;default->504;};importDoc(kb,UUID.randomUUID().toString(),false,expected);}
        origin().mode="GOOD";origin().body="x".repeat(3000);importDoc(kb,UUID.randomUUID().toString(),false,413);
        for(String url:List.of("file:///etc/passwd","http://user:secret@127.0.0.1:"+origin().port()+"/a.md","http://127.0.0.2/a.md","http://169.254.169.254/latest"))assertThatThrownBy(()->downloader.download(url,null,null,null)).isInstanceOf(KnowledgeBaseException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kb_document",Integer.class)).isZero();
        try(var files=Files.list(Path.of("target/knowledge-base/remote-spool"))){assertThat(files.toList()).isEmpty();}
    }
    @Test void failedDownloadRetriesAreFiniteAndDoNotChangeTheCurrentFile()throws Exception {
        UUID doc=fixture();var old=doc(doc);origin().mode="FAIL";UUID run=submit(doc);
        for(int i=0;i<3;i++){executeRefresh();jdbc.update("UPDATE kb_refresh_run SET next_run_at=clock_timestamp() WHERE id=? AND status='PENDING'",run);}
        assertThat(runStatus(run)).isEqualTo("FAILED");assertThat(doc(doc).get("object_key")).isEqualTo(old.get("object_key"));assertThat(jdbc.queryForObject("SELECT consecutive_failures FROM kb_refresh_schedule WHERE document_id=?",Integer.class,doc)).isEqualTo(1);assertThat(refresh.claimNext()).isNull();
    }
    @Test void duplicateDispatchDoesNotReplaceAnAlreadySubmittedCandidate()throws Exception {
        UUID doc=fixture();changed();UUID run=submit(doc);var claim=refresh.claimNext();refresh.execute(claim);var before=jdbc.queryForMap("SELECT candidate_object_key,job_id FROM kb_refresh_run WHERE id=?",run);int requests=origin().calls.get();
        refresh.execute(claim);assertThat(origin().calls.get()).isEqualTo(requests);assertThat(jdbc.queryForMap("SELECT candidate_object_key,job_id FROM kb_refresh_run WHERE id=?",run)).isEqualTo(before);kernel.execute(jobs.claimNext());assertThat(runStatus(run)).isEqualTo("SUCCESS");
    }
    @Test void publicationDatabaseFailureRollsBackFileBaselineChunksAndVectorsTogether()throws Exception {
        UUID doc=fixture();var before=doc(doc);changed();UUID run=submit(doc);executeRefresh();var job=jobs.claimNext();
        jdbc.execute("CREATE FUNCTION reject_source_switch() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'injected'; END $$");
        jdbc.execute("CREATE TRIGGER reject_source_switch BEFORE UPDATE OF object_key ON kb_document FOR EACH ROW EXECUTE FUNCTION reject_source_switch()");
        try{assertThatThrownBy(()->kernel.execute(job)).isInstanceOf(RuntimeException.class);}finally{jdbc.execute("DROP TRIGGER reject_source_switch ON kb_document");jdbc.execute("DROP FUNCTION reject_source_switch()");}
        refresh.reconcile();assertThat(runStatus(run)).isEqualTo("FAILED");assertThat(doc(doc).get("object_key")).isEqualTo(before.get("object_key"));assertThat(doc(doc).get("active_generation")).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kb_generation WHERE document_id=?",Integer.class,doc)).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT last_etag FROM kb_refresh_schedule WHERE document_id=?",String.class,doc)).isEqualTo("\"v1\"");
    }
    @Test void importScopeAndPayloadIdempotencyAreCheckedBeforeAnotherDownload()throws Exception {
        UUID kb=base();String key=UUID.randomUUID().toString();importDoc(kb,key,false,201);int calls=origin().calls.get();
        call(post("/api/kbs/"+kb+"/documents/import-url").header("Idempotency-Key",key),Map.of("url",origin().url(),"filename","different.md","ingest",false),409);
        call(post("/api/kbs/"+kb+"/documents/import-url").header("Idempotency-Key",UUID.randomUUID().toString()),Map.of("url",origin().url(),"filename","remote.md","ingest",false),404,"bob");
        assertThat(origin().calls.get()).isEqualTo(calls);
    }
    @Test void sameOriginRedirectDoesNotReuseValidatorsFromAnotherResourceUri() {
        origin().mode="LOCAL_REDIRECT";
        try(var result=downloader.download(origin().url(),"\"v1\"",origin().modified,null)) {
            assertThat(result.httpStatus()).isEqualTo(200);assertThat(origin().lastIfNoneMatch).isNull();assertThat(origin().lastIfModifiedSince).isNull();assertThat(result.etag()).isNull();assertThat(result.lastModified()).isNull();
        }
    }
    @Test void realProcessRestartRecoversRefreshAndPublishesExactlyOneNewGeneration()throws Exception {
        UUID doc=fixture();changed();UUID run=submit(doc);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        model.beforeEmbed=()->{entered.countDown();try{release.await(60,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}};
        Process first=null,second=null;
        try {
            first=startProcess("before-crash");assertThat(entered.await(45,TimeUnit.SECONDS)).isTrue();assertThat(runStatus(run)).isEqualTo("WAITING_INGESTION");
            first.destroyForcibly();assertThat(first.waitFor(10,TimeUnit.SECONDS)).isTrue();release.countDown();model.beforeEmbed=()->{};
            await(()->Boolean.TRUE.equals(jdbc.queryForObject("SELECT lease_until<=clock_timestamp() FROM kb_refresh_run WHERE id=?",Boolean.class,run)),25);
            second=startProcess("after-restart");await(()->runStatus(run).equals("SUCCESS"),60);
            assertThat(jdbc.queryForObject("SELECT attempts FROM kb_refresh_run WHERE id=?",Integer.class,run)).isEqualTo(2);
            assertThat(doc(doc).get("active_generation")).isEqualTo(3L); // failed target 2 is fenced, replacement target is 3
            var hits=call(post("/api/knowledge/search"),Map.of("query","bread","kbIds",List.of(doc(doc).get("kb_id"))),200);assertThat(hits).hasSize(1);assertThat(hits.get(0).path("generation").asLong()).isEqualTo(3);
            Files.writeString(Path.of("target/knowledge-base/refresh-process-restart.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("firstPid",first.pid(),"secondPid",second.pid(),"run",call(get(path(doc)+"/refresh-runs"),null,200),"hits",hits,"environment","Two actual Java processes; PostgreSQL/MinIO; local HTTP source and embedding fixture")));
        }finally {release.countDown();model.beforeEmbed=()->{};for(var process:new Process[]{first,second})if(process!=null && process.isAlive()){process.destroyForcibly();process.waitFor(10,TimeUnit.SECONDS);}}
    }
    private Process startProcess(String label)throws Exception {
        var directory=Path.of("target/knowledge-base").toAbsolutePath();Files.createDirectories(directory);var args=directory.resolve("refresh-"+label+".args");
        String cp=System.getProperty("java.class.path").replace("\\","\\\\");
        Files.writeString(args,"-Xmx256m\n-Dfile.encoding=UTF-8\n-cp\n\""+cp+"\"\ncom.lzj.rag.backend.BackendApplication\n--server.port=0\n--kb.worker.enabled=true\n--kb.worker.poll-delay=200\n--kb.worker.lease=3s\n--kb.worker.heartbeat=500ms\n--kb.remote.poll-delay=200\n--kb.remote.heartbeat=1000\n");
        var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString(),"@"+args);
        var env=builder.environment();env.put("SPRING_PROFILES_ACTIVE","test");env.put("SPRING_DATASOURCE_URL",DATABASE.getJdbcUrl());env.put("SPRING_DATASOURCE_USERNAME",DATABASE.getUsername());env.put("SPRING_DATASOURCE_PASSWORD",DATABASE.getPassword());
        env.put("KB_STORAGE_ENABLED","true");env.put("KB_S3_BUCKET","kb-remote-test");env.put("KB_S3_ENDPOINT","http://"+MINIO.getHost()+":"+MINIO.getMappedPort(9000));env.put("KB_S3_PATH_STYLE","true");
        env.put("AWS_ACCESS_KEY_ID","kb-test-access");env.put("AWS_SECRET_ACCESS_KEY","kb-test-secret-password");env.put("KB_AI_BASE_URL",model.url());
        env.put("KB_REMOTE_ENABLED","true");env.put("KB_REMOTE_ALLOWED_HOSTS","127.0.0.1");env.put("KB_REMOTE_TRUSTED_PRIVATE_HOSTS","127.0.0.1");env.put("KB_REMOTE_ALLOWED_PORTS",origin().port()+"");env.put("KB_REMOTE_ALLOW_HTTP","true");env.put("KB_REFRESH_LEASE","15s");
        return builder.redirectErrorStream(true).redirectOutput(directory.resolve("refresh-"+label+".log").toFile()).start();
    }
    private void await(java.util.function.BooleanSupplier condition,int seconds)throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);while(System.nanoTime()<end){if(condition.getAsBoolean())return;Thread.sleep(50);}assertThat(condition.getAsBoolean()).as("Condition within %s seconds; inspect refresh process logs",seconds).isTrue();
    }
}
