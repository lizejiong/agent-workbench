package com.lzj.rag.backend;

import com.fasterxml.jackson.databind.*;
import com.lzj.rag.backend.ingestion.IngestionKernel;
import com.lzj.rag.backend.model.*;
import com.lzj.rag.backend.operations.KnowledgeBaseMetrics;
import com.lzj.rag.backend.security.KnowledgeBasePrincipal;
import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(properties={"kb.storage.enabled=true","kb.storage.bucket=kb-delivery-test"})
@AutoConfigureMockMvc @ActiveProfiles("test") @Import(DocumentUploadTest.StorageTestConfiguration.class)
@Testcontainers(disabledWithoutDocker=true)
class ManagementDeliveryTest {
    static EmbeddingStub model;
    static synchronized String modelUrl(){if(model==null)model=new EmbeddingStub();return model.url();}
    @AfterAll static void close(){if(model!=null)model.close();}
    @Container static final PostgreSQLContainer<?> DATABASE=new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:0.8.7-pg17-bookworm").asCompatibleSubstituteFor("postgres"));
    @Container static final GenericContainer<?> MINIO=new GenericContainer<>(DockerImageName.parse("minio/minio:RELEASE.2025-09-07T16-13-09Z"))
            .withEnv("MINIO_ROOT_USER","kb-test-access").withEnv("MINIO_ROOT_PASSWORD","kb-test-secret-password")
            .withCommand("server","/data").withExposedPorts(9000).waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));
    @DynamicPropertySource static void infrastructure(DynamicPropertyRegistry p){
        p.add("spring.datasource.url",DATABASE::getJdbcUrl);p.add("spring.datasource.username",DATABASE::getUsername);p.add("spring.datasource.password",DATABASE::getPassword);
        p.add("kb.storage.endpoint",()->"http://"+MINIO.getHost()+":"+MINIO.getMappedPort(9000));p.add("kb.storage.path-style-access",()->true);p.add("kb.ai.base-url",ManagementDeliveryTest::modelUrl);
    }
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired JdbcTemplate jdbc;@Autowired IngestionKernel kernel;
    @Autowired UserDetailsService users;@Autowired PlatformTransactionManager manager;@Autowired MeterRegistry meters;@Autowired RerankClient rerank;
    @Autowired DocumentUploadTest.FaultStorage storage;
    @BeforeEach void clear(){model.reset();model.vectorizer=KnowledgeSearchTest::topicVector;storage.reset();TestDatabaseCleanup.clear(jdbc);}
    private KnowledgeBasePrincipal actor(){return (KnowledgeBasePrincipal)users.loadUserByUsername("alice");}
    private JsonNode call(MockHttpServletRequestBuilder request,Object body,int status,String user)throws Exception{
        if(body!=null)request.contentType("application/json").content(json.writeValueAsBytes(body));
        var response=mvc.perform(request.with(httpBasic(user,user+"-local-test-password"))).andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);return json.readTree(response.getContentAsByteArray());
    }
    private UUID base()throws Exception{return UUID.fromString(call(post("/api/kbs"),Map.of("name","sensitive-title-"+UUID.randomUUID()),201,"alice").at("/data/id").asText());}
    private UUID document(UUID kb)throws Exception{
        var file=new MockMultipartFile("file","database.md","text/plain","# Private\n\nSensitive PostgreSQL source SECRET_DOCUMENT_90210.".getBytes(StandardCharsets.UTF_8));
        UUID doc=UUID.fromString(call(multipart("/api/kbs/"+kb+"/documents").file(file).header("Idempotency-Key",UUID.randomUUID().toString()),null,201,"alice").at("/data/id").asText());
        kernel.ingest(actor(),doc);return doc;
    }
    @Test void entryAndCallCollectionAreAuthenticatedAndContainOnlyPublicRoutesAndSafeDefaults()throws Exception{
        assertThat(mvc.perform(get("/api/knowledge/management")).andReturn().getResponse().getStatus()).isEqualTo(401);
        var entry=call(get("/api/knowledge/management"),null,200,"alice").path("data");
        assertThat(entry.path("delivery").asText()).isEqualTo("API_ONLY");assertThat(entry.at("/ingestionDefaults/splitter/maxChars").asInt()).isEqualTo(1024);
        assertThat(entry.toString()).doesNotContain("secret","localhost:9200","objectKey","password","tenant-ops");
        var collection=call(get(entry.path("collection").asText()),null,200,"alice");
        assertThat(collection.at("/info/schema").asText()).endsWith("v2.1.0/collection.json");assertThat(collection.path("item").size()).isGreaterThan(30);
        assertThat(mvc.perform(get(entry.path("collection").asText())).andReturn().getResponse().getStatus()).isEqualTo(401);
    }
    @Test void operatorMetricsAreProtectedWithoutGrantingAccessToOtherUsersKnowledge()throws Exception{
        assertThat(mvc.perform(get("/actuator/metrics")).andReturn().getResponse().getStatus()).isEqualTo(401);
        call(get("/actuator/metrics"),null,403,"alice");
        var names=call(get("/actuator/metrics"),null,200,"operator").path("names");assertThat(names.toString()).contains("kb.ingestion.jobs","kb.ingestion.stage","kb.cleanup.resources","kb.refresh.runs");
        call(get("/actuator/env"),null,403,"operator");
        UUID kb=base();call(get("/api/kbs/"+kb),null,404,"operator");
        call(get("/api/kbs/"+kb+"/audit"),null,404,"bob");call(get("/api/kbs/"+kb+"/audit"),null,404,"carol");
    }
    @Test void durableAuditShowsCommittedOwnerChangesAndHidesAllSourceContents()throws Exception{
        UUID kb=base(),doc=document(kb);
        assertThat(call(get("/api/kbs/"+kb),null,200,"alice").at("/data/documentCount").asLong()).isEqualTo(1);
        assertThat(call(get("/api/kbs?name=sensitive-title"),null,200,"alice").at("/data/records/0/documentCount").asLong()).isEqualTo(1);
        assertThat(call(get("/api/documents/"+doc),null,200,"alice").at("/data/lastIngestedAt").isTextual()).isTrue();
        var audit=call(get("/api/kbs/"+kb+"/audit?size=100"),null,200,"alice").path("data");
        assertThat(audit.path("total").asInt()).isGreaterThan(5);assertThat(audit.toString()).contains("BASE_INSERT","INGESTION_STATE","user-alice");
        assertThat(audit.toString()).doesNotContain("SECRET_DOCUMENT_90210","Sensitive PostgreSQL","sensitive-title-","kb-test-secret","sourceUrl","objectKey");
        call(get("/api/kbs/"+kb+"/audit?size=101"),null,400,"alice");
    }
    @Test void failedMutationDoesNotCommitAnAuditOrNewBody()throws Exception{
        UUID kb=base(),doc=document(kb);UUID chunk=jdbc.queryForObject("SELECT id FROM kb_chunk WHERE document_id=?",UUID.class,doc);
        long count=jdbc.queryForObject("SELECT count(*) FROM kb_content_audit",Long.class);
        jdbc.execute("CREATE FUNCTION delivery_fail_vector() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'injected'; END $$");
        jdbc.execute("CREATE TRIGGER delivery_fail BEFORE INSERT ON kb_vector FOR EACH ROW EXECUTE FUNCTION delivery_fail_vector()");
        try{call(patch("/api/documents/"+doc+"/chunks/"+chunk),Map.of("version",1,"content","Baking new confidential replacement"),503,"alice");}
        finally{jdbc.execute("DROP TRIGGER delivery_fail ON kb_vector");jdbc.execute("DROP FUNCTION delivery_fail_vector()");}
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kb_content_audit",Long.class)).isEqualTo(count);
        assertThat(jdbc.queryForObject("SELECT content FROM kb_chunk WHERE id=?",String.class,chunk)).contains("SECRET_DOCUMENT_90210");
    }
    @Test void metricsReflectDurableStagesAndCountCompleteEmbeddingAndRerankFailures()throws Exception{
        UUID kb=base(),doc=document(kb);model.mode="WRONG_DIM";
        double embeddingBefore=Optional.ofNullable(meters.find("kb.model.failures").tag("operation","embedding").counter()).map(Counter::count).orElse(0.0);
        assertThatThrownBy(()->kernel.ingest(actor(),doc)).isInstanceOf(RuntimeException.class);
        assertThat(meters.get("kb.model.failures").tag("operation","embedding").counter().count()).isEqualTo(embeddingBefore+1);
        model.rerankMode="SHORT";
        assertThatThrownBy(()->rerank.rerank("query",List.of(new RerankClient.Input(UUID.randomUUID(),"private candidate")),"delivery-test",Instant.now().plusSeconds(5))).isInstanceOf(RuntimeException.class);
        assertThat(meters.get("kb.model.failures").tag("operation","rerank").counter().count()).isGreaterThan(0);
        var registry=new SimpleMeterRegistry();
        try{
            new KnowledgeBaseMetrics(jdbc,manager,registry);
            assertThat(registry.get("kb.ingestion.jobs").tag("status","FAILED").gauge().value()).isEqualTo(1);
            assertThat(registry.get("kb.ingestion.stage").tags("stage","EMBED","status","FAILED").functionTimer().count()).isEqualTo(1);
            assertThat(registry.get("kb.ingestion.stage").tags("stage","PUBLISH","status","SUCCEEDED").functionTimer().count()).isEqualTo(1);
            assertThat(registry.get("kb.metrics.snapshot.available").gauge().value()).isEqualTo(1);
            assertThat(registry.getMeters().stream().flatMap(m->m.getId().getTags().stream()).map(t->t.getKey()).toList()).doesNotContain("documentId","kbId","jobId","tenantId");
        }finally{registry.close();}
    }
    @Test void committedStageLogsCorrelateAllIdentifiersWithoutPrintingTheDocument()throws Exception{
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger("knowledge-base.operations");
        var appender=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();appender.start();logger.addAppender(appender);
        try{
            UUID kb=base(),doc=document(kb);String logs=appender.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).reduce("",(a,b)->a+"\n"+b);
            assertThat(logs).contains("kbId="+kb,"documentId="+doc,"jobId=","generation=1","stage=EMBED","stage=PUBLISH","status=SUCCEEDED");
            assertThat(logs).doesNotContain("SECRET_DOCUMENT_90210","Sensitive PostgreSQL","kb-test-secret");
        }finally{logger.detachAppender(appender);appender.stop();}
    }
}
