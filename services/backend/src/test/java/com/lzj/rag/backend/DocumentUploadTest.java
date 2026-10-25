package com.lzj.rag.backend;

import com.fasterxml.jackson.databind.*;
import com.lzj.rag.backend.knowledgebase.KnowledgeBaseException;
import com.lzj.rag.backend.storage.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real PostgreSQL + actual AWS SDK against MinIO; only injected failures are controlled in the adapter. */
@SpringBootTest(properties = {"kb.storage.enabled=true", "kb.storage.bucket=kb-upload-test", "kb.storage.max-upload-bytes=1024"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(DocumentUploadTest.StorageTestConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class DocumentUploadTest {
    static com.lzj.rag.backend.model.EmbeddingStub EMBEDDINGS;
    static synchronized String embeddingUrl(){if(EMBEDDINGS==null)EMBEDDINGS=new com.lzj.rag.backend.model.EmbeddingStub();return EMBEDDINGS.url();}
    @AfterAll static void stopEmbeddings(){if(EMBEDDINGS!=null)EMBEDDINGS.close();}
    @Container static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>(DockerImageName
            .parse("pgvector/pgvector:0.8.7-pg17-bookworm").asCompatibleSubstituteFor("postgres"));
    @Container static final GenericContainer<?> MINIO = new GenericContainer<>(DockerImageName.parse("minio/minio:RELEASE.2025-09-07T16-13-09Z"))
            .withEnv("MINIO_ROOT_USER", "kb-test-access").withEnv("MINIO_ROOT_PASSWORD", "kb-test-secret-password")
            .withCommand("server", "/data").withExposedPorts(9000).waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));
    @DynamicPropertySource static void infrastructure(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", DATABASE::getJdbcUrl);
        properties.add("spring.datasource.username", DATABASE::getUsername);
        properties.add("spring.datasource.password", DATABASE::getPassword);
        properties.add("kb.storage.endpoint", () -> "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000));
        properties.add("kb.storage.path-style-access", () -> true);
        properties.add("kb.ai.base-url",DocumentUploadTest::embeddingUrl);
        properties.add("kb.ai.call-timeout",()->"1s");
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class StorageTestConfiguration {
        @Bean @Primary FaultStorage faultStorage(StorageProperties properties) {
            var client = S3Client.builder().endpointOverride(java.net.URI.create(properties.endpoint()))
                    .region(software.amazon.awssdk.regions.Region.US_EAST_1)
                    .credentialsProvider(software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(
                            software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create("kb-test-access", "kb-test-secret-password")))
                    .forcePathStyle(true).build();
            client.createBucket(b -> b.bucket(properties.bucket()));
            return new FaultStorage(client, properties.bucket());
        }
    }
    static class FaultStorage implements ObjectStorageClient, AutoCloseable {
        final S3Client client;
        final S3ObjectStorageClient delegate;
        final Set<String> keys = ConcurrentHashMap.newKeySet();
        boolean failDelete;
        boolean failAfterPut;
        int puts, opens;
        volatile java.util.concurrent.CountDownLatch putEntered;
        volatile java.util.concurrent.CountDownLatch releasePut;
        final java.util.concurrent.atomic.AtomicBoolean pausePut = new java.util.concurrent.atomic.AtomicBoolean();
        FaultStorage(S3Client client, String bucket) { this.client = client; delegate = new S3ObjectStorageClient(client, bucket); }
        public void put(String key, Path file, String mime, String hash) {
            puts++; delegate.put(key, file, mime, hash); keys.add(key);
            if (failAfterPut) throw new KnowledgeBaseException(503, "KB_STORAGE_UNAVAILABLE", "Injected lost PUT response");
            if (pausePut.compareAndSet(true, false)) {
                putEntered.countDown();
                try {
                    if (!releasePut.await(20, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Timed out waiting for concurrent acceptance");
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            }
        }
        public InputStream open(String key) { opens++; return delegate.open(key); }
        public void delete(String key) {
            if (failDelete) throw new KnowledgeBaseException(503, "KB_STORAGE_UNAVAILABLE", "Injected deletion failure");
            delegate.delete(key); keys.remove(key);
        }
        void reset() { failDelete = false; failAfterPut = false; for (String key : List.copyOf(keys)) delete(key); puts = 0; opens = 0; }
        public void close() { client.close(); }
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired FaultStorage storage;
    @Autowired com.lzj.rag.backend.ingestion.IngestionKernel kernel;
    @Autowired com.lzj.rag.backend.ingestion.IngestionJobService jobs;
    @Autowired com.lzj.rag.backend.vector.VectorStore vectorStore;
    @Autowired com.lzj.rag.backend.document.DocumentService documents;
    @Autowired com.lzj.rag.backend.knowledgebase.KnowledgeBaseService bases;
    @Autowired org.springframework.security.core.userdetails.UserDetailsService users;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired com.lzj.rag.backend.ingestion.IngestionProperties ingestionLimits;
    @Autowired com.lzj.rag.backend.ingestion.GenerationCleanupService cleanup;
    @Autowired com.lzj.rag.backend.chunk.ChunkIndexWriter writer;
    @BeforeEach void reset() {
        EMBEDDINGS.reset();
        storage.reset();
        TestDatabaseCleanup.clear(jdbc);
    }

    @Test void uploadStoresActualMetadataAndAuthorizedDownloadReturnsOriginalBytes() throws Exception {
        String kb = create("Files");
        byte[] bytes = "# 中文手册\n\nKnowledge base evidence.\n".getBytes(StandardCharsets.UTF_8);
        var doc = upload(kb, "../notes.md", bytes, null, "upload-normal-0001", "alice", 201).path("data");
        String id = doc.path("id").asText();
        assertThat(doc.path("originalFilename").asText()).isEqualTo("notes.md");
        assertThat(doc.path("mimeType").asText()).isEqualTo("text/plain"); // filename is deliberately not passed to Tika
        assertThat(doc.path("fileSize").asLong()).isEqualTo(bytes.length);
        assertThat(doc.path("contentHash").asText()).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        assertThat(doc.path("objectKey").asText()).doesNotContain("notes", "..");
        assertThat(doc.path("processStatus").asText()).isEqualTo("PENDING");
        assertThat(doc.path("activeGeneration").isNull()).isTrue();
        assertThat(doc.path("chunkCount").asLong()).isZero();
        assertThat(doc.at("/ingestionSpec/splitter/maxChars").asInt()).isEqualTo(1024);
        var response = mvc.perform(get("/api/documents/" + id + "/file").with(auth("alice")))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff")).andReturn().getResponse();
        assertThat(response.getContentAsByteArray()).isEqualTo(bytes);
        assertThat(storage.client.headObject(b -> b.bucket("kb-upload-test").key(doc.path("objectKey").asText())).metadata())
                .containsEntry("sha256", doc.path("contentHash").asText());
        call(delete("/api/kbs/" + kb).param("version", "1"), "alice", 409);
    }

    @Test void allowedModelCatalogBindsSnapshotAndRejectsArbitraryOrChangedBinding() throws Exception {
        var profiles = call(get("/api/kb-models"), "alice", 200).path("data");
        assertThat(profiles.get(0).path("dimension").asInt()).isEqualTo(1024);
        call(post("/api/kbs").contentType("application/json").content("{\"name\":\"bad\",\"embeddingProfileId\":\"invented\"}"), "alice", 400);
        String kb = create("Model");
        var base = call(get("/api/kbs/" + kb), "alice", 200).path("data");
        assertThat(base.path("embeddingConfigRevision").asText()).hasSize(64);
        assertThat(base.path("vectorNamespace").asText()).startsWith("kb_");
        call(patch("/api/kbs/" + kb).contentType("application/json")
                .content("{\"version\":1,\"embeddingProfileId\":\"another\"}"), "alice", 409);
        call(patch("/api/kbs/" + kb).contentType("application/json").content("{\"version\":1,\"name\":\"Renamed\"}"), "alice", 200);
        assertThat(call(get("/api/kbs").param("name", "name"), "alice", 200).at("/data/total").asInt()).isEqualTo(1);
    }

    @Test void uploadRejectsEmptyOversizedUnsupportedAndInvalidUtf8BeforeWritingObjects() throws Exception {
        String kb = create("Reject");
        upload(kb, "empty.txt", new byte[0], null, "reject-empty-0001", "alice", 400);
        upload(kb, "big.txt", new byte[1025], null, "reject-size-00001", "alice", 413);
        upload(kb, "bad.pdf", "text".getBytes(StandardCharsets.UTF_8), null, "reject-type-00001", "alice", 415);
        upload(kb, "binary.txt", new byte[]{0, 1, 2, 3}, null, "reject-binary-001", "alice", 415);
        upload(kb, "bad.txt", new byte[]{(byte) 0xc3, 0x28}, null, "reject-utf8-00001", "alice", 415);
        assertThat(storage.puts).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kb_upload_operation", Integer.class)).isZero();
    }

    @Test void invalidIngestionMetadataAndForgedOwnershipAreRejectedEarly() throws Exception {
        String kb = create("Spec");
        for (String metadata : List.of("{", "null", "{\"tenantId\":\"evil\"}", "{\"name\":\"　\"}",
                "{\"ingestionSpec\":{\"schemaVersion\":0}}", "{\"ingestionSpec\":{\"parser\":\"PDF\"}}",
                "{\"ingestionSpec\":{\"splitter\":{\"maxChars\":128,\"overlapChars\":128}}}")) {
            upload(kb, "doc.txt", new byte[]{65}, metadata, "invalid-spec-0001", "alice", 400);
        }
        assertThat(storage.puts).isZero();
    }

    @Test void otherOwnerOtherTenantAndUnauthenticatedCannotUploadReadOrMutate() throws Exception {
        String kb = create("Private");
        String id = upload(kb, "doc.txt", new byte[]{65}, null, "private-doc-0001", "alice", 201).at("/data/id").asText();
        int opens = storage.opens;
        for (String user : List.of("bob", "carol")) {
            upload(kb, "other.txt", new byte[]{66}, null, "private-other-0001", user, 404);
            call(get("/api/kbs/" + kb + "/documents"), user, 404);
            call(get("/api/documents/" + id), user, 404);
            call(get("/api/documents/" + id + "/file"), user, 404);
            call(patch("/api/documents/" + id).contentType("application/json").content("{\"version\":1,\"name\":\"stolen\"}"), user, 404);
            call(get("/api/kbs/" + kb + "/storage-operations"), user, 404);
        }
        mvc.perform(get("/api/documents/" + id + "/file")).andExpect(status().isUnauthorized());
        assertThat(storage.opens).isEqualTo(opens);
        assertThat(storage.puts).isEqualTo(1);
    }

    @Test void repeatedPayloadIsReplayedAndDifferentPayloadOrDuplicateNameIsRejected() throws Exception {
        String kb = create("Idempotent");
        var first = upload(kb, "doc.txt", new byte[]{65}, null, "idempotent-doc-001", "alice", 201).path("data");
        var replay = upload(kb, "doc.txt", new byte[]{65}, null, "idempotent-doc-001", "alice", 200).path("data");
        assertThat(replay.path("id")).isEqualTo(first.path("id"));
        assertThat(upload(kb, "doc.txt", new byte[]{66}, null, "idempotent-doc-001", "alice", 409).path("code").asText()).isEqualTo("KB_IDEMPOTENCY_CONFLICT");
        assertThat(upload(kb, "DOC.txt", new byte[]{65}, null, "idempotent-doc-002", "alice", 409).path("code").asText()).isEqualTo("KB_DOCUMENT_NAME_EXISTS");
        assertThat(storage.puts).isEqualTo(1);
        call(post("/api/kbs/" + kb + "/storage-operations/" + first.path("id").asText() + "/retry-cleanup"), "alice", 409);
        assertThat(storage.keys).hasSize(1);
    }

    @Test void listFiltersAndPatchKeepPendingAndIncrementOnlyChangedSpecRevision() throws Exception {
        String kb = create("Maintenance");
        String id = upload(kb, "doc.txt", new byte[]{65}, "{\"name\":\"Guide_%\"}", "metadata-doc-0001", "alice", 201).at("/data/id").asText();
        upload(kb, "other.md", new byte[]{66}, null, "metadata-doc-0002", "alice", 201);
        assertThat(call(get("/api/kbs/" + kb + "/documents").param("name", "_%").param("status", "PENDING").param("size", "1"), "alice", 200).at("/data/total").asInt()).isEqualTo(1);
        call(get("/api/kbs/" + kb + "/documents").param("status", "INVALID"), "alice", 400);
        var updated = call(patch("/api/documents/" + id).contentType("application/json")
                .content("{\"version\":1,\"name\":\"New title\",\"ingestionSpec\":{\"splitter\":{\"maxChars\":800}}}"), "alice", 200).path("data");
        assertThat(updated.path("configRevision").asInt()).isEqualTo(2);
        assertThat(updated.path("processStatus").asText()).isEqualTo("PENDING");
        assertThat(call(patch("/api/documents/" + id).contentType("application/json")
                .content("{\"version\":2,\"ingestionSpec\":{\"splitter\":{\"maxChars\":800}}}"), "alice", 200).at("/data/configRevision").asInt()).isEqualTo(2);
        call(patch("/api/documents/" + id).contentType("application/json").content("{\"version\":1,\"name\":\"stale\"}"), "alice", 409);
        call(patch("/api/documents/" + id).contentType("application/json").content("{\"version\":3,\"name\":\"OTHER.md\"}"), "alice", 409);
    }

    @Test void realDatabaseInsertFailureDeletesTheStoredObjectAndRetainsRecoveryRecord() throws Exception {
        databaseFailure(false);
    }
    @Test void failedCompensationRemainsQueryableAndCanBeRetriedWithoutDeletingCommittedFiles() throws Exception {
        databaseFailure(true);
    }
    private void databaseFailure(boolean failDelete) throws Exception {
        String kb = create("Failure");
        jdbc.execute("CREATE FUNCTION kb_test_fail_insert() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'injected private database error'; END $$");
        jdbc.execute("CREATE TRIGGER kb_test_fail BEFORE INSERT ON kb_document FOR EACH ROW EXECUTE FUNCTION kb_test_fail_insert()");
        storage.failDelete = failDelete;
        try {
            var response = upload(kb, "doc.txt", new byte[]{65}, null, "failure-upload-001", "alice", 503);
            assertThat(response.toString()).doesNotContain("injected", "private database");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM kb_document", Integer.class)).isZero();
            assertThat(storage.keys).hasSize(failDelete ? 1 : 0);
            var operation = call(get("/api/kbs/" + kb + "/storage-operations"), "alice", 200).at("/data/records/0");
            assertThat(operation.path("idempotencyKey").asText()).isEqualTo("failure-upload-001");
            assertThat(operation.path("status").asText()).isEqualTo(failDelete ? "CLEANUP_PENDING" : "CLEANED");
            if (failDelete) {
                call(delete("/api/kbs/" + kb).param("version", "1"), "alice", 409);
                assertThat(operation.path("attempts").asInt()).isEqualTo(1);
                storage.failDelete = false;
                call(post("/api/kbs/" + kb + "/storage-operations/" + operation.path("id").asText() + "/retry-cleanup"), "alice", 200);
                assertThat(storage.keys).isEmpty();
            }
        } finally {
            storage.failDelete = false;
            jdbc.execute("DROP TRIGGER kb_test_fail ON kb_document"); jdbc.execute("DROP FUNCTION kb_test_fail_insert()");
        }
    }

    @Test void expiredUploadIntentIsRecoverableAndActiveLeaseCannotBeCleaned() throws Exception {
        String kb = create("Recovery"); UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO kb_upload_operation(id,kb_id,tenant_id,owner_id,idempotency_key,payload_hash,object_key,status,lease_until)
                VALUES (?,?,'tenant-a','user-alice','recovery-upload-001',?,'missing-object','UPLOADING',CURRENT_TIMESTAMP + interval '5 minutes')
                """, id, UUID.fromString(kb), "a".repeat(64));
        call(post("/api/kbs/" + kb + "/storage-operations/" + id + "/retry-cleanup"), "alice", 409);
        jdbc.update("UPDATE kb_upload_operation SET lease_until=CURRENT_TIMESTAMP - interval '1 second' WHERE id=?", id);
        assertThat(call(post("/api/kbs/" + kb + "/storage-operations/" + id + "/retry-cleanup"), "alice", 200).at("/data/status").asText()).isEqualTo("CLEANED");
        call(delete("/api/kbs/" + kb).param("version", "1"), "alice", 200);
    }

    @Test void ambiguousPutFailureAlsoCompensatesTheRemoteObject() throws Exception {
        String kb = create("Lost response");
        storage.failAfterPut = true;
        upload(kb, "doc.txt", new byte[]{65}, null, "lost-response-0001", "alice", 503);
        assertThat(storage.keys).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM kb_upload_operation", String.class)).isEqualTo("CLEANED");
    }

    @Test void pendingUploadDoesNotHoldADatabaseTransactionAndConcurrentReplayCannotWriteTwice() throws Exception {
        String kb = create("Concurrent upload");
        storage.putEntered = new java.util.concurrent.CountDownLatch(1);
        storage.releasePut = new java.util.concurrent.CountDownLatch(1);
        storage.pausePut.set(true);
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(() -> upload(kb, "doc.txt", new byte[]{65}, null, "concurrent-upload-01", "alice", 201));
            assertThat(storage.putEntered.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var operation = call(get("/api/kbs/" + kb + "/storage-operations"), "alice", 200).at("/data/records/0");
            assertThat(operation.path("status").asText()).isEqualTo("UPLOADING");
            call(post("/api/kbs/" + kb + "/storage-operations/" + operation.path("id").asText() + "/retry-cleanup"), "alice", 409);
            assertThat(upload(kb, "doc.txt", new byte[]{65}, null, "concurrent-upload-01", "alice", 409).path("code").asText()).isEqualTo("KB_UPLOAD_NOT_COMPLETED");
            call(delete("/api/kbs/" + kb).param("version", "1"), "alice", 409);
            storage.releasePut.countDown();
            first.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(storage.puts).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM kb_document", Integer.class)).isEqualTo(1);
        } finally { storage.releasePut.countDown(); executor.shutdownNow(); }
    }

    @Test void legacyUnboundBaseRequiresAnExplicitOwnedVersionedBinding() throws Exception {
        String kb = create("Legacy");
        jdbc.update("""
                UPDATE kb_knowledge_base SET embedding_profile_id=NULL,embedding_model_id=NULL,embedding_provider=NULL,
                embedding_dimension=NULL,embedding_config_revision=NULL,embedding_config_json=NULL,vector_namespace=NULL WHERE id=?
                """, UUID.fromString(kb));
        assertThat(call(get("/api/kbs/" + kb), "alice", 200).at("/data/bindingStatus").asText()).isEqualTo("UNBOUND");
        assertThat(upload(kb, "doc.txt", new byte[]{65}, null, "legacy-upload-0001", "alice", 409).path("code").asText()).isEqualTo("KB_MODEL_UNBOUND");
        String body = "{\"version\":1,\"embeddingProfileId\":\"qwen-embed-1024\"}";
        call(put("/api/kbs/" + kb + "/embedding-binding").contentType("application/json").content(body), "bob", 404);
        var bound = call(put("/api/kbs/" + kb + "/embedding-binding").contentType("application/json").content(body), "alice", 200).path("data");
        assertThat(bound.path("bindingStatus").asText()).isEqualTo("BOUND");
        assertThat(bound.path("version").asInt()).isEqualTo(2);
        call(put("/api/kbs/" + kb + "/embedding-binding").contentType("application/json").content(body), "alice", 409);
        upload(kb, "doc.txt", new byte[]{65}, null, "legacy-upload-0001", "alice", 201);
    }

    @Test void migrationUpgradesLegacyMetadataWithoutInventingAModelBinding() {
        String schema = "kb_legacy_upgrade";
        var migration = org.flywaydb.core.Flyway.configure().dataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword())
                .schemas(schema).defaultSchema(schema).table("kb_flyway_schema_history").target("1").load();
        try {
            migration.migrate();
            UUID id = UUID.randomUUID();
            jdbc.update("INSERT INTO kb_legacy_upgrade.kb_knowledge_base(id,tenant_id,owner_id,name) VALUES (?,'legacy-tenant','legacy-owner','Legacy')", id);
            org.flywaydb.core.Flyway.configure().dataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword())
                    .schemas(schema).defaultSchema(schema).table("kb_flyway_schema_history").load().migrate();
            var row = jdbc.queryForMap("SELECT * FROM kb_legacy_upgrade.kb_knowledge_base WHERE id=?", id);
            assertThat(row.get("embedding_model_id")).isNull();
            assertThat(row.get("vector_namespace")).isNull();
            assertThat(row.get("name")).isEqualTo("Legacy");
            assertThat(row.get("created_by")).isEqualTo("legacy-owner");
        } finally { jdbc.execute("DROP SCHEMA IF EXISTS kb_legacy_upgrade CASCADE"); }
    }

    private com.lzj.rag.backend.security.KnowledgeBasePrincipal actor(String username){
        return (com.lzj.rag.backend.security.KnowledgeBasePrincipal)users.loadUserByUsername(username);
    }
    private UUID sampleDocument() throws Exception {
        String kb=create("Ingestion "+UUID.randomUUID());
        byte[] source;try(var stream=getClass().getResourceAsStream("/knowledge-base/sample.md")){source=stream.readAllBytes();}
        String id=upload(kb,"guide.md",source,null,"sample-ingest-0001","alice",201).at("/data/id").asText();
        call(patch("/api/documents/"+id).contentType("application/json").content("{\"version\":1,\"ingestionSpec\":{\"splitter\":{\"maxChars\":128,\"overlapChars\":16,\"tableRows\":2,\"toleranceChars\":64}}}"),"alice",200);
        return UUID.fromString(id);
    }
    private com.lzj.rag.backend.vector.VectorStore.Scope scope(UUID doc){
        var base=bases.requireOwned(actor("alice"),documents.requireOwned(actor("alice"),doc).getKbId());
        return new com.lzj.rag.backend.vector.VectorStore.Scope(actor("alice"),base.getId(),base.getVectorNamespace(),base.getEmbeddingModelId(),base.getEmbeddingConfigRevision());
    }
    @Test void synchronousMarkdownPipelinePersistsRealPgvectorAndExportsDisplayAndEmbeddingSamples() throws Exception {
        UUID doc=sampleDocument();var result=kernel.ingest(actor("alice"),doc);
        assertThat(result.chunks()).hasSizeGreaterThanOrEqualTo(6);
        assertThat(documents.get(actor("alice"),doc).processStatus()).isEqualTo("READY");
        assertThat(documents.get(actor("alice"),doc).activeGeneration()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kb_vector",Integer.class)).isEqualTo(result.chunks().size());
        assertThat(jdbc.queryForObject("SELECT min(public.vector_dims(embedding)) FROM kb_vector",Integer.class)).isEqualTo(1024);
        for(var row:jdbc.queryForList("SELECT c.embedding_text,v.embedding::text AS vector FROM kb_chunk c JOIN kb_vector v ON v.chunk_id=c.id")){
            assertThat(json.readTree((String)row.get("vector")).get(0).asInt()).isEqualTo(((String)row.get("embedding_text")).length());
        }
        float[] query=new float[1024];query[0]=1;
        var hits=vectorStore.query(scope(doc),query,100);
        assertThat(hits).hasSize(result.chunks().size()).allSatisfy(h->{assertThat(h.generation()).isEqualTo(1);assertThat(h.cosineDistance()).isCloseTo(0,org.assertj.core.data.Offset.offset(0.00001));});
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kb_ingestion_stage_log WHERE status='SUCCEEDED'",Integer.class)).isEqualTo(6);
        assertThat(jobs.getOwnedJob(actor("alice"),doc,result.jobId()).status()).isEqualTo("SUCCEEDED");
        String jobPath="/api/documents/"+doc+"/ingestion-jobs/"+result.jobId();
        assertThat(call(get(jobPath),"alice",200).at("/data/status").asText()).isEqualTo("SUCCEEDED");
        assertThat(call(get(jobPath+"/stages"),"alice",200).at("/data/total").asInt()).isEqualTo(6);
        assertThat(call(get("/api/documents/"+doc+"/ingestion-jobs").param("status","SUCCEEDED"),"alice",200).at("/data/total").asInt()).isEqualTo(1);
        call(get(jobPath),"bob",404);
        call(post("/api/documents/"+doc+"/ingestion-jobs"),"alice",400);
        assertThatThrownBy(()->jobs.getOwnedJob(actor("bob"),doc,result.jobId())).isInstanceOf(KnowledgeBaseException.class);
        Path export=Path.of("target/knowledge-base/sample-chunks.json");Files.createDirectories(export.getParent());
        Files.writeString(export,json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("source","src/test/resources/knowledge-base/sample.md","vectorSource","Local HTTP protocol stub; not a live model","chunks",result.chunks())));
    }
    @Test void failedModelGenerationKeepsOldPublishedEvidenceAndRetryNeverReusesGeneration() throws Exception {
        UUID doc=sampleDocument();var first=kernel.ingest(actor("alice"),doc);EMBEDDINGS.mode="WRONG_DIM";
        assertThatThrownBy(()->kernel.ingest(actor("alice"),doc)).isInstanceOfSatisfying(KnowledgeBaseException.class,e->assertThat(e.code()).isEqualTo("KB_MODEL_INVALID_RESPONSE"));
        assertThat(documents.get(actor("alice"),doc).activeGeneration()).isEqualTo(1);
        assertThat(documents.get(actor("alice"),doc).processStatus()).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kb_generation",Integer.class)).isEqualTo(1);
        EMBEDDINGS.mode="GOOD";var third=kernel.ingest(actor("alice"),doc);assertThat(third.generation()).isEqualTo(3);
        float[] query=new float[1024];query[0]=1;
        assertThat(vectorStore.query(scope(doc),query,100)).allSatisfy(h->assertThat(h.generation()).isEqualTo(3));
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
        tx.executeWithoutResult(status->{
            vectorStore.deleteByChunk(scope(doc),first.chunks().get(0).id());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM kb_vector WHERE generation=1",Integer.class)).isEqualTo(first.chunks().size()-1);
            vectorStore.deleteByDocument(scope(doc),doc,1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM kb_vector WHERE generation=1",Integer.class)).isZero();status.setRollbackOnly();
        });
    }
    @Test void emptyAndTimedOutIngestionsPersistFailuresWithoutPublishing() throws Exception {
        String kb=create("Empty parser");UUID empty=UUID.fromString(upload(kb,"empty-content.txt","  \n\n".getBytes(StandardCharsets.UTF_8),null,"empty-content-001","alice",201).at("/data/id").asText());
        assertThatThrownBy(()->kernel.ingest(actor("alice"),empty)).isInstanceOfSatisfying(KnowledgeBaseException.class,e->assertThat(e.code()).isEqualTo("KB_EMPTY_CONTENT"));
        assertThat(documents.get(actor("alice"),empty).processStatus()).isEqualTo("FAILED");
        UUID doc=sampleDocument();EMBEDDINGS.mode="TIMEOUT";
        assertThatThrownBy(()->kernel.ingest(actor("alice"),doc)).isInstanceOfSatisfying(KnowledgeBaseException.class,e->assertThat(e.code()).isEqualTo("KB_MODEL_TIMEOUT"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kb_vector",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT error_code FROM kb_ingestion_job WHERE document_id=?",String.class,doc)).isEqualTo("KB_MODEL_TIMEOUT");
    }
    @Test void inputChangeDuringHttpEmbeddingFencesPublication() throws Exception {
        UUID doc=sampleDocument();var once=new java.util.concurrent.atomic.AtomicBoolean();
        EMBEDDINGS.beforeEmbed=()->{if(once.compareAndSet(false,true))documents.patch(actor("alice"),doc,new com.lzj.rag.backend.document.DocumentTypes.PatchRequest(2L,"Changed during model call",null));};
        assertThatThrownBy(()->kernel.ingest(actor("alice"),doc)).isInstanceOfSatisfying(KnowledgeBaseException.class,e->assertThat(e.code()).isEqualTo("KB_INGESTION_INPUT_CHANGED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kb_chunk",Integer.class)).isZero();
        assertThat(documents.get(actor("alice"),doc).processStatus()).isEqualTo("PENDING");
    }
    @Test void firstPublicationPinsAiRevisionAndFutureRunsCannotMixAnotherRevision() throws Exception {
        UUID doc=sampleDocument();kernel.ingest(actor("alice"),doc);
        assertThat(bases.get(actor("alice"),scope(doc).kbId()).embeddingAiConfigRevision()).isEqualTo("a".repeat(64));
        int calls=EMBEDDINGS.requests.size();EMBEDDINGS.mode="REVISION_CHANGED";
        assertThatThrownBy(()->kernel.ingest(actor("alice"),doc)).isInstanceOfSatisfying(KnowledgeBaseException.class,e->assertThat(e.code()).isEqualTo("KB_MODEL_CONFIG_CHANGED"));
        assertThat(EMBEDDINGS.requests).hasSize(calls);
        assertThat(documents.get(actor("alice"),doc).activeGeneration()).isEqualTo(1);
    }
    @Test void databaseVectorInsertFailureRollsBackChunksGenerationAndPublication() throws Exception {
        UUID doc=sampleDocument();var first=kernel.ingest(actor("alice"),doc);
        jdbc.execute("CREATE FUNCTION kb_test_vector_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'private vector database failure'; END $$");
        jdbc.execute("CREATE TRIGGER kb_test_vector_fail BEFORE INSERT ON kb_vector FOR EACH ROW EXECUTE FUNCTION kb_test_vector_fail()");
        try{
            assertThatThrownBy(()->kernel.ingest(actor("alice"),doc)).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM kb_generation",Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM kb_chunk",Integer.class)).isEqualTo(first.chunks().size());
            assertThat(documents.get(actor("alice"),doc).activeGeneration()).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM kb_ingestion_job WHERE status='FAILED'",Integer.class)).isEqualTo(1);
        }finally{jdbc.execute("DROP TRIGGER kb_test_vector_fail ON kb_vector");jdbc.execute("DROP FUNCTION kb_test_vector_fail()");}
    }
    @Test void expiredInternalRunRecoversDurablyAndOldWorkerCannotPublish() throws Exception {
        UUID doc=sampleDocument();var old=jobs.start(actor("alice"),doc);
        assertThatThrownBy(()->kernel.ingest(actor("bob"),doc)).isInstanceOf(KnowledgeBaseException.class);
        assertThatThrownBy(()->jobs.start(actor("alice"),doc)).isInstanceOfSatisfying(KnowledgeBaseException.class,e->assertThat(e.code()).isEqualTo("KB_JOB_ACTIVE"));
        jdbc.update("UPDATE kb_ingestion_job SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?",old.jobId());jobs.recoverExpired(actor("alice"),doc);
        assertThat(jobs.getOwnedJob(actor("alice"),doc,old.jobId()).status()).isEqualTo("FAILED");
        assertThat(jobs.getOwnedJob(actor("alice"),doc,old.jobId()).errorCode()).isEqualTo("KB_INGESTION_EXPIRED");
        assertThatThrownBy(()->jobs.begin(old,"EMBED",1,"texts")).isInstanceOf(KnowledgeBaseException.class);
        assertThat(kernel.ingest(actor("alice"),doc).generation()).isEqualTo(2);
    }

    @Test void durableSubmissionIsIdempotentScopedAndCapturesConfiguration() throws Exception {
        UUID doc=sampleDocument();String path="/api/documents/"+doc+"/ingestion-jobs";
        var first=call(post(path).contentType("application/json").content("{\"requestId\":\"submit-idem-0001\"}"),"alice",202).path("data");
        UUID id=UUID.fromString(first.path("id").asText());
        assertThat(first.path("status").asText()).isEqualTo("PENDING");assertThat(first.path("attempts").asInt()).isZero();
        assertThat(first.path("documentConfigRevision").asLong()).isEqualTo(2);
        documents.patch(actor("alice"),doc,new com.lzj.rag.backend.document.DocumentTypes.PatchRequest(2L,"Changed after submit",null));
        assertThat(call(post(path).contentType("application/json").content("{\"requestId\":\"submit-idem-0001\"}"),"alice",200).at("/data/id").asText()).isEqualTo(id.toString());
        call(post(path).contentType("application/json").content("{\"requestId\":\"submit-conflict-0002\"}"),"alice",409);
        call(post(path).contentType("application/json").content("{\"requestId\":\"x\"}"),"alice",400);
        call(post(path).contentType("application/json").content("{\"requestId\":\"unknown-tenant-001\",\"tenantId\":\"tenant-a\"}"),"alice",400);
        for(String user:List.of("bob","carol")) {
            call(get("/api/ingestion-jobs/"+id),user,404);call(get("/api/ingestion-jobs/"+id+"/stages"),user,404);
            call(post("/api/ingestion-jobs/"+id+"/cancel"),user,404);
            call(post(path).contentType("application/json").content("{\"requestId\":\"cross-owner-0001\"}"),user,404);
        }
        mvc.perform(get("/api/ingestion-jobs/"+id)).andExpect(status().isUnauthorized());
        UUID other=sampleDocument();
        assertThatThrownBy(()->jobs.submit(actor("alice"),other,"submit-idem-0001",null)).isInstanceOfSatisfying(KnowledgeBaseException.class,e->assertThat(e.code()).isEqualTo("KB_IDEMPOTENCY_CONFLICT"));
        assertThat(jobs.claimNext()).isNull(); // Input changed while queued: cancel before touching S3/model.
        assertThat(jobs.getOwnedJob(actor("alice"),id).status()).isEqualTo("CANCELLED");
        assertThat(EMBEDDINGS.requests).isEmpty();
    }
    @Test void concurrentSubmissionsAndClaimsHaveOneDatabaseWinnerAndDuplicateDispatchDoesNotFailIt() throws Exception {
        UUID doc=sampleDocument();var pool=java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            var gate=new java.util.concurrent.CountDownLatch(1);
            var submissions=new ArrayList<java.util.concurrent.Future<com.lzj.rag.backend.ingestion.IngestionJobService.Submission>>();
            for(int i=0;i<8;i++)submissions.add(pool.submit(()->{gate.await();return jobs.submit(actor("alice"),doc,"concurrent-submit-0001",null);}));
            gate.countDown();var ids=new HashSet<UUID>();for(var future:submissions)ids.add(future.get(15,java.util.concurrent.TimeUnit.SECONDS).job().id());
            assertThat(ids).hasSize(1);
            var claims=new ArrayList<java.util.concurrent.Future<com.lzj.rag.backend.ingestion.IngestionContext>>();
            for(int i=0;i<8;i++)claims.add(pool.submit(jobs::claimNext));
            var winners=new ArrayList<com.lzj.rag.backend.ingestion.IngestionContext>();
            for(var future:claims){var c=future.get(15,java.util.concurrent.TimeUnit.SECONDS);if(c!=null)winners.add(c);}
            assertThat(winners).hasSize(1);var claimed=winners.get(0);
            var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
            EMBEDDINGS.beforeEmbed=()->{entered.countDown();awaitLatch(release);};
            var execution=pool.submit(()->kernel.execute(claimed));
            try {
                assertThat(entered.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(()->kernel.execute(claimed)).isInstanceOfSatisfying(KnowledgeBaseException.class,e->assertThat(e.code()).isEqualTo("KB_JOB_EXPIRED"));
                assertThat(jobs.getOwnedJob(actor("alice"),claimed.jobId()).status()).isEqualTo("RUNNING");
            } finally {release.countDown();}
            execution.get(15,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(jobs.getOwnedJob(actor("alice"),claimed.jobId()).attempts()).isEqualTo(1);
            assertThat(jobs.getOwnedJob(actor("alice"),claimed.jobId()).status()).isEqualTo("SUCCEEDED");
        } finally {pool.shutdownNow();}
    }
    @Test void rateLimitsUseFiniteBackoffAndManualRetryCreatesANewSnapshot() throws Exception {
        UUID doc=sampleDocument();UUID id=jobs.submit(actor("alice"),doc,"rate-limit-0001",null).job().id();
        EMBEDDINGS.mode="UPSTREAM_RATE_LIMIT";
        for(int attempt=1;attempt<=3;attempt++) {
            var c=jobs.claimNext();assertThat(c.attempt()).isEqualTo(attempt);
            assertThatThrownBy(()->kernel.execute(c)).isInstanceOf(KnowledgeBaseException.class);
            var job=jobs.getOwnedJob(actor("alice"),id);assertThat(job.errorCode()).isEqualTo("KB_MODEL_RATE_LIMITED");
            assertThat(job.errorMessage()).doesNotContain("private upstream");
            if(attempt<3) {
                assertThat(job.status()).isEqualTo("PENDING");assertThat(job.nextRunAt()).isAfter(java.time.OffsetDateTime.now());
                assertThat(jobs.claimNext()).isNull();makeDue(id);
            } else {assertThat(job.status()).isEqualTo("FAILED");assertThat(job.nextRunAt()).isNull();}
        }
        assertThat(jobs.stages(actor("alice"),doc,id,1,100).getRecords()).filteredOn(s->s.stage().equals("EMBED")).hasSize(3);
        var spec=new com.lzj.rag.backend.document.DocumentTypes.IngestionSpec(1,"AUTO",new com.lzj.rag.backend.document.DocumentTypes.Splitter("WHOLE_DOCUMENT",1024,128));
        documents.patch(actor("alice"),doc,new com.lzj.rag.backend.document.DocumentTypes.PatchRequest(2L,null,spec));
        var retried=call(post("/api/ingestion-jobs/"+id+"/retry").contentType("application/json").content("{\"requestId\":\"manual-retry-0001\"}"),"alice",202).path("data");
        UUID next=UUID.fromString(retried.path("id").asText());assertThat(next).isNotEqualTo(id);
        assertThat(retried.path("retryOf").asText()).isEqualTo(id.toString());assertThat(retried.path("documentConfigRevision").asLong()).isEqualTo(3);
        assertThat(call(post("/api/ingestion-jobs/"+id+"/retry").contentType("application/json").content("{\"requestId\":\"manual-retry-0001\"}"),"alice",200).at("/data/id").asText()).isEqualTo(next.toString());
        EMBEDDINGS.mode="GOOD";assertThat(kernel.execute(jobs.claimNext()).chunks()).hasSize(1);
        assertThat(jobs.getOwnedJob(actor("alice"),next).status()).isEqualTo("SUCCEEDED");
        call(post("/api/ingestion-jobs/"+next+"/retry").contentType("application/json").content("{\"requestId\":\"retry-success-0001\"}"),"alice",409);
    }
    @Test void expiredLeaseLateResultsCannotModifyNewAttemptOrPublish() throws Exception {
        UUID doc=sampleDocument();var oldPublished=kernel.ingest(actor("alice"),doc);
        UUID id=jobs.submit(actor("alice"),doc,"lease-fencing-0001",null).job().id();var old=jobs.claimNext();
        var replacement=new java.util.concurrent.atomic.AtomicReference<com.lzj.rag.backend.ingestion.IngestionKernel.Result>();
        var once=new java.util.concurrent.atomic.AtomicBoolean();
        EMBEDDINGS.beforeEmbed=()->{if(once.compareAndSet(false,true)) {
            jdbc.update("UPDATE kb_ingestion_job SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?",id);
            assertThat(jobs.renew(old)).isFalse();assertThat(jobs.claimNext()).isNull();makeDue(id);
            var current=jobs.claimNext();assertThat(current.claimToken()).isGreaterThan(old.claimToken());
            // Exercise fencing while the replacement is RUNNING, not only after it becomes terminal.
            assertThatThrownBy(()->jobs.begin(old,"PERSIST",1,"chunks")).isInstanceOf(KnowledgeBaseException.class);
            assertThatThrownBy(()->jobs.end(old,"EMBED",1,"vectors")).isInstanceOf(KnowledgeBaseException.class);
            assertThatThrownBy(()->jobs.pinAiRevision(old,"b".repeat(64))).isInstanceOf(KnowledgeBaseException.class);
            jobs.fail(old,new KnowledgeBaseException(503,"KB_MODEL_UNAVAILABLE","late error"));
            assertThat(jobs.getOwnedJob(actor("alice"),id).status()).isEqualTo("RUNNING");
            assertThat(jobs.getOwnedJob(actor("alice"),id).attempts()).isEqualTo(2);
            replacement.set(kernel.execute(current));
        }};
        assertThatThrownBy(()->kernel.execute(old)).isInstanceOfSatisfying(KnowledgeBaseException.class,e->assertThat(e.code()).isEqualTo("KB_JOB_EXPIRED"));
        var published=replacement.get();assertThat(published).isNotNull();
        assertThatThrownBy(()->writer.writeAndPublish(old,published.chunks(),new com.lzj.rag.backend.model.EmbeddingClient.Result(
                published.chunks().stream().map(c->{float[] vector=new float[1024];vector[0]=1;return vector;}).toList(),published.aiConfigRevision())))
                .isInstanceOf(KnowledgeBaseException.class);
        jobs.fail(old,new KnowledgeBaseException(503,"KB_MODEL_UNAVAILABLE","late failure"));
        assertThat(jobs.getOwnedJob(actor("alice"),id).status()).isEqualTo("SUCCEEDED");
        assertThat(jobs.getOwnedJob(actor("alice"),id).attempts()).isEqualTo(2);
        assertThat(documents.get(actor("alice"),doc).activeGeneration()).isEqualTo(2);
        float[] query=new float[1024];query[0]=1;
        assertThat(vectorStore.query(scope(doc),query,100)).hasSize(published.chunks().size()).allSatisfy(h->assertThat(h.generation()).isEqualTo(2));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kb_ingestion_stage_log WHERE job_id=? AND attempt=1 AND stage='EMBED' AND status='FAILED'",Integer.class,id)).isEqualTo(1);
        assertThat(oldPublished.generation()).isEqualTo(1);
    }
    @Test void permanentReingestionFailureKeepsOldEvidenceAndSuccessfulRetryReturnsOnlyNewGeneration() throws Exception {
        UUID doc=sampleDocument();var first=kernel.ingest(actor("alice"),doc);UUID id=jobs.submit(actor("alice"),doc,"permanent-0001",null).job().id();
        EMBEDDINGS.mode="WRONG_DIM";assertThatThrownBy(()->kernel.execute(jobs.claimNext())).isInstanceOf(KnowledgeBaseException.class);
        assertThat(jobs.getOwnedJob(actor("alice"),id).status()).isEqualTo("FAILED");assertThat(jobs.getOwnedJob(actor("alice"),id).attempts()).isEqualTo(1);
        float[] query=new float[1024];query[0]=1;
        assertThat(vectorStore.query(scope(doc),query,100)).hasSize(first.chunks().size()).allSatisfy(h->assertThat(h.generation()).isEqualTo(1));
        EMBEDDINGS.mode="GOOD";jobs.submit(actor("alice"),doc,"permanent-retry-001",id);var next=kernel.execute(jobs.claimNext());
        assertThat(next.generation()).isEqualTo(3);
        assertThat(vectorStore.query(scope(doc),query,100)).hasSize(next.chunks().size()).allSatisfy(h->assertThat(h.generation()).isEqualTo(3));
        jdbc.update("UPDATE kb_generation_cleanup SET next_run_at=clock_timestamp() WHERE document_id=?",doc);
        for(var task:cleanup.due())cleanup.clean(task);
        assertThat(jdbc.queryForList("SELECT generation FROM kb_generation WHERE document_id=?",Long.class,doc)).containsExactly(3L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kb_vector WHERE document_id=?",Integer.class,doc)).isEqualTo(next.chunks().size());
    }
    @Test void cancellationFencesRunningWorkerAndDoesNotBecomeAModelRetry() throws Exception {
        UUID doc=sampleDocument();kernel.ingest(actor("alice"),doc);UUID id=jobs.submit(actor("alice"),doc,"cancel-running-001",null).job().id();var claimed=jobs.claimNext();
        EMBEDDINGS.beforeEmbed=()->jobs.cancel(actor("alice"),id);
        assertThatThrownBy(()->kernel.execute(claimed)).isInstanceOf(KnowledgeBaseException.class);
        assertThat(jobs.getOwnedJob(actor("alice"),id).status()).isEqualTo("CANCELLED");assertThat(jobs.getOwnedJob(actor("alice"),id).nextRunAt()).isNull();
        assertThat(jobs.claimNext()).isNull();assertThat(documents.get(actor("alice"),doc).activeGeneration()).isEqualTo(1);
        assertThat(jobs.stages(actor("alice"),doc,id,1,100).getRecords()).filteredOn(s->s.stage().equals("EMBED")).allSatisfy(s->assertThat(s.status()).isEqualTo("CANCELLED"));
        assertThat(call(post("/api/ingestion-jobs/"+id+"/cancel"),"alice",200).at("/data/status").asText()).isEqualTo("CANCELLED");
    }
    @Test void disabledDocumentCannotActivateNewGeneration() throws Exception {
        UUID doc=sampleDocument();kernel.ingest(actor("alice"),doc);jobs.submit(actor("alice"),doc,"disabled-publish-001",null);
        EMBEDDINGS.beforeEmbed=()->jdbc.update("UPDATE kb_document SET enabled=false WHERE id=?",doc);
        assertThatThrownBy(()->kernel.execute(jobs.claimNext())).isInstanceOf(KnowledgeBaseException.class);
        assertThat(documents.get(actor("alice"),doc).activeGeneration()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kb_generation WHERE document_id=?",Integer.class,doc)).isEqualTo(1);
    }
    @Test void boundedExecutorKeepsUnclaimedWorkPendingAndHeartbeatRenewsRunningLease() throws Exception {
        UUID first=sampleDocument(),second=sampleDocument();UUID id=jobs.submit(actor("alice"),first,"capacity-first-001",null).job().id();
        UUID waiting=jobs.submit(actor("alice"),second,"capacity-second-001",null).job().id();
        var config=new com.lzj.rag.backend.ingestion.IngestionWorkerProperties(true,1,0,3,java.time.Duration.ofSeconds(30),java.time.Duration.ofSeconds(5),java.time.Duration.ofSeconds(2),java.time.Duration.ofMinutes(5),java.time.Duration.ZERO,5);
        var executor=new com.lzj.rag.backend.ingestion.IngestionWorkerConfiguration().ingestionExecutor(config);executor.initialize();
        var worker=new com.lzj.rag.backend.ingestion.IngestionWorker(jobs,kernel,config,executor);
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        EMBEDDINGS.beforeEmbed=()->{entered.countDown();awaitLatch(release);};
        try {
            worker.scan();assertThat(entered.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();worker.scan();
            assertThat(jobs.getOwnedJob(actor("alice"),waiting).status()).isEqualTo("PENDING");assertThat(jobs.getOwnedJob(actor("alice"),waiting).attempts()).isZero();
            jdbc.update("UPDATE kb_ingestion_job SET lease_until=clock_timestamp()+interval '2 seconds' WHERE id=?",id);worker.heartbeat();
            assertThat(jobs.getOwnedJob(actor("alice"),id).leaseUntil()).isAfter(java.time.OffsetDateTime.now().plusSeconds(10));
            release.countDown();awaitCondition(()->jobs.getOwnedJob(actor("alice"),id).status().equals("SUCCEEDED"),15);
            EMBEDDINGS.beforeEmbed=()->{};
            awaitCondition(()->{worker.scan();return jobs.getOwnedJob(actor("alice"),waiting).status().equals("SUCCEEDED");},15);
        } finally {release.countDown();executor.shutdown();}
    }
    @Test void executorRejectionReturnsAnUnstartedClaimWithoutBurningAnAttempt() throws Exception {
        UUID doc=sampleDocument();UUID id=jobs.submit(actor("alice"),doc,"reject-worker-0001",null).job().id();
        var config=new com.lzj.rag.backend.ingestion.IngestionWorkerProperties(true,1,0,3,java.time.Duration.ofSeconds(30),java.time.Duration.ofSeconds(5),java.time.Duration.ofSeconds(2),java.time.Duration.ofMinutes(5),java.time.Duration.ZERO,5);
        var executor=new com.lzj.rag.backend.ingestion.IngestionWorkerConfiguration().ingestionExecutor(config);executor.initialize();executor.shutdown();
        new com.lzj.rag.backend.ingestion.IngestionWorker(jobs,kernel,config,executor).scan();
        var job=jobs.getOwnedJob(actor("alice"),id);assertThat(job.status()).isEqualTo("PENDING");assertThat(job.attempts()).isZero();assertThat(job.leaseUntil()).isNull();
        assertThat(kernel.execute(jobs.claimNext()).generation()).isEqualTo(1);
    }
    @Test void cleanupFailureIsDurableRetryableAndNeverDeletesActiveGeneration() throws Exception {
        UUID doc=sampleDocument();kernel.ingest(actor("alice"),doc);kernel.ingest(actor("alice"),doc);
        UUID task=jdbc.queryForObject("SELECT id FROM kb_generation_cleanup WHERE document_id=? AND generation=1",UUID.class,doc);
        jdbc.update("UPDATE kb_generation_cleanup SET next_run_at=clock_timestamp() WHERE id=?",task);
        jdbc.execute("CREATE FUNCTION kb_test_gc_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'private GC details'; END $$");
        jdbc.execute("CREATE TRIGGER kb_test_gc_fail BEFORE DELETE ON kb_chunk FOR EACH ROW EXECUTE FUNCTION kb_test_gc_fail()");
        try {
            assertThatThrownBy(()->cleanup.clean(task)).isInstanceOf(org.springframework.dao.DataAccessException.class);cleanup.failed(task);
            assertThat(jdbc.queryForObject("SELECT error_code FROM kb_generation_cleanup WHERE id=?",String.class,task)).isEqualTo("KB_GENERATION_CLEANUP_FAILED");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM kb_generation WHERE document_id=?",Integer.class,doc)).isEqualTo(2);
        } finally {jdbc.execute("DROP TRIGGER kb_test_gc_fail ON kb_chunk");jdbc.execute("DROP FUNCTION kb_test_gc_fail()");}
        jdbc.update("UPDATE kb_generation_cleanup SET next_run_at=clock_timestamp() WHERE id=?",task);assertThat(cleanup.clean(task)).isTrue();
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);tx.executeWithoutResult(s->jobs.queueCleanup(doc,2));
        jdbc.update("UPDATE kb_generation_cleanup SET next_run_at=clock_timestamp() WHERE document_id=?",doc);
        for(var next:cleanup.due())cleanup.clean(next);
        assertThat(jdbc.queryForList("SELECT generation FROM kb_generation WHERE document_id=?",Long.class,doc)).containsExactly(2L);
    }
    @Test void actualJavaProcessCrashAndRestartRecoversTheSameDurableJob() throws Exception {
        UUID doc=sampleDocument();UUID id=jobs.submit(actor("alice"),doc,"process-restart-001",null).job().id();
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        var once=new java.util.concurrent.atomic.AtomicBoolean();
        EMBEDDINGS.beforeEmbed=()->{if(once.compareAndSet(false,true)){entered.countDown();awaitLatch(release);}};
        Process first=null,second=null;
        try {
            first=startWorkerProcess("before-crash");
            assertThat(entered.await(45,java.util.concurrent.TimeUnit.SECONDS)).as("First process reached HTTP embedding; see target/knowledge-base/process-before-crash.log").isTrue();
            assertThat(jobs.getOwnedJob(actor("alice"),id).status()).isEqualTo("RUNNING");
            first.destroyForcibly();assertThat(first.waitFor(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();release.countDown();
            second=startWorkerProcess("after-restart");
            awaitCondition(()->jobs.getOwnedJob(actor("alice"),id).status().equals("SUCCEEDED"),45);
            var result=jobs.getOwnedJob(actor("alice"),id);assertThat(result.attempts()).isEqualTo(2);
            assertThat(jobs.stages(actor("alice"),doc,id,1,100).getRecords()).filteredOn(s->s.attempt()==1 && s.stage().equals("EMBED"))
                    .allSatisfy(s->{assertThat(s.status()).isEqualTo("FAILED");assertThat(s.errorCode()).isEqualTo("KB_INGESTION_EXPIRED");});
            float[] query=new float[1024];query[0]=1;var hits=vectorStore.query(scope(doc),query,100);
            assertThat(hits).hasSize((int)documents.get(actor("alice"),doc).chunkCount()).allSatisfy(h->assertThat(h.generation()).isEqualTo(1));
            assertThat(hits.stream().map(h->h.chunkId()).distinct().count()).isEqualTo(hits.size());
            Files.writeString(Path.of("target/knowledge-base/process-restart-evidence.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(
                    Map.of("firstPid",first.pid(),"secondPid",second.pid(),"jobId",id,"attempts",result.attempts(),"status",result.status(),"hits",hits.size(),"generation",1,"vectorSource","Local HTTP stub; real PostgreSQL/MinIO and two Java processes")));
        } finally {
            release.countDown();
            for(var process:new Process[]{first,second})if(process!=null && process.isAlive()) {process.destroyForcibly();process.waitFor(10,java.util.concurrent.TimeUnit.SECONDS);}
        }
    }
    @Test void migrationBackfillsExistingV3RunningJobInputsAndPreservesItsDeadline() throws Exception {
        UUID doc=sampleDocument();var context=jobs.start(actor("alice"),doc);String schema="kb_v3_upgrade";
        try {
            org.flywaydb.core.Flyway.configure().dataSource(DATABASE.getJdbcUrl(),DATABASE.getUsername(),DATABASE.getPassword())
                    .schemas(schema).defaultSchema(schema).table("kb_flyway_schema_history").target("3").load().migrate();
            jdbc.update("INSERT INTO kb_v3_upgrade.kb_knowledge_base SELECT * FROM public.kb_knowledge_base WHERE id=?",context.kbId());
            String documentColumns="id,kb_id,tenant_id,owner_id,name,original_filename,object_key,mime_type,file_size,content_hash,ingestion_spec,created_by,updated_by,created_at,updated_at,next_generation";
            jdbc.update("INSERT INTO kb_v3_upgrade.kb_document("+documentColumns+") SELECT "+documentColumns+" FROM public.kb_document WHERE id=?",doc);
            String jobColumns="id,request_id,document_id,kb_id,tenant_id,owner_id,document_config_revision,document_version,embedding_config_revision,target_generation,object_key,content_hash,ingestion_spec,status,stage,attempts,max_attempts,lease_owner,lease_until,claim_token,started_at";
            jdbc.update("INSERT INTO kb_v3_upgrade.kb_ingestion_job("+jobColumns+") SELECT "+jobColumns+" FROM public.kb_ingestion_job WHERE id=?",context.jobId());
            org.flywaydb.core.Flyway.configure().dataSource(DATABASE.getJdbcUrl(),DATABASE.getUsername(),DATABASE.getPassword())
                    .schemas(schema).defaultSchema(schema).table("kb_flyway_schema_history").load().migrate();
            var row=jdbc.queryForMap("SELECT * FROM kb_v3_upgrade.kb_ingestion_job WHERE id=?",context.jobId());
            var snapshot=json.readTree(row.get("input_snapshot").toString());
            assertThat(snapshot.path("originalFilename").asText()).isEqualTo("guide.md");assertThat(snapshot.at("/binding/modelId").asText()).isEqualTo("qwen-embed");
            assertThat(snapshot.at("/binding/localRevision").asText()).isEqualTo(context.binding().localRevision());
            assertThat(row.get("attempt_deadline")).isEqualTo(row.get("lease_until"));assertThat(row.get("max_attempts")).isEqualTo(1);
        } finally {jdbc.execute("DROP SCHEMA IF EXISTS kb_v3_upgrade CASCADE");}
    }
    private Process startWorkerProcess(String label)throws Exception {
        Path directory=Path.of("target/knowledge-base").toAbsolutePath();Files.createDirectories(directory);
        Path args=directory.resolve("process-"+label+".args");
        String classpath=System.getProperty("java.class.path").replace("\\","\\\\");
        Files.writeString(args,"-Xmx256m\n-Dfile.encoding=UTF-8\n-cp\n\""+classpath+"\"\ncom.lzj.rag.backend.BackendApplication\n--server.port=0\n--kb.worker.enabled=true\n--kb.worker.concurrency=1\n--kb.worker.lease=3s\n--kb.worker.heartbeat=500ms\n--kb.worker.poll-delay=100\n--kb.worker.backoff=100ms\n--kb.ai.call-timeout=30s\n");
        var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString(),"@"+args);
        var env=builder.environment();env.put("SPRING_PROFILES_ACTIVE","test");
        env.put("SPRING_DATASOURCE_URL",DATABASE.getJdbcUrl());env.put("SPRING_DATASOURCE_USERNAME",DATABASE.getUsername());env.put("SPRING_DATASOURCE_PASSWORD",DATABASE.getPassword());
        env.put("KB_STORAGE_ENABLED","true");env.put("KB_S3_BUCKET","kb-upload-test");env.put("KB_S3_ENDPOINT","http://"+MINIO.getHost()+":"+MINIO.getMappedPort(9000));env.put("KB_S3_PATH_STYLE","true");
        env.put("AWS_ACCESS_KEY_ID","kb-test-access");env.put("AWS_SECRET_ACCESS_KEY","kb-test-secret-password");env.put("KB_AI_BASE_URL",EMBEDDINGS.url());
        return builder.redirectErrorStream(true).redirectOutput(directory.resolve("process-"+label+".log").toFile()).start();
    }
    private void makeDue(UUID id){jdbc.update("UPDATE kb_ingestion_job SET next_run_at=clock_timestamp() WHERE id=? AND status='PENDING'",id);}
    private static void awaitLatch(java.util.concurrent.CountDownLatch latch) {
        try{if(!latch.await(60,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("Fixture latch timeout");}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
    }
    private static void awaitCondition(java.util.function.BooleanSupplier condition,int seconds)throws Exception {
        long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(seconds);
        while(System.nanoTime()<until){if(condition.getAsBoolean())return;Thread.sleep(50);}
        assertThat(condition.getAsBoolean()).as("Condition within %s seconds",seconds).isTrue();
    }
    private String create(String name) throws Exception {
        return call(post("/api/kbs").contentType("application/json").content(json.writeValueAsString(Map.of("name", name))), "alice", 201).at("/data/id").asText();
    }
    private JsonNode upload(String kb, String name, byte[] bytes, String metadata, String key, String user, int status) throws Exception {
        var request = multipart("/api/kbs/" + kb + "/documents").file(new MockMultipartFile("file", name, "application/fake", bytes));
        if (metadata != null) request.file(new MockMultipartFile("metadata", "", "application/json", metadata.getBytes(StandardCharsets.UTF_8)));
        return call(request.header("Idempotency-Key", key), user, status);
    }
    private JsonNode call(MockHttpServletRequestBuilder request, String user, int status) throws Exception {
        var response = mvc.perform(request.with(auth(user))).andExpect(status().is(status)).andReturn().getResponse();
        return json.readTree(response.getContentAsByteArray());
    }
    private static org.springframework.test.web.servlet.request.RequestPostProcessor auth(String user) {
        return httpBasic(user, user + "-local-test-password");
    }
}
