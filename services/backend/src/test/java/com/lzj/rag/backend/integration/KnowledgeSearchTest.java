package com.lzj.rag.backend.integration;

import com.lzj.rag.backend.integration.DocumentUploadTest;
import com.lzj.rag.backend.integration.TestDatabaseCleanup;
import com.lzj.rag.backend.model.client.EmbeddingStub;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.lzj.rag.backend.ingestion.pipeline.IngestionKernel;
import com.lzj.rag.backend.model.client.EmbeddingStub;
import com.lzj.rag.backend.security.model.KnowledgeBasePrincipal;

/** Actual S3 uploads, parsing, HTTP query embeddings and pgvector SQL. Vectors are an explicit local topic fixture. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"kb.storage.enabled=true","kb.storage.bucket=kb-search-test","kb.storage.max-upload-bytes=4096",
        "kb.search.max-top-k=6","kb.search.max-kb-ids=2","kb.search.max-document-ids=3","kb.search.max-query-chars=256","kb.rag.context.neighbor-radius=1",
        "kb.rag.internal.enabled=true","kb.rag.internal.service-token-hashes.agent-test=abababababababababababababababababababababababababababababababab",
        "kb.models.profiles.other.enabled=true","kb.models.profiles.other.model-id=other-embed","kb.models.profiles.other.provider=bailian",
        "kb.models.profiles.other.model-name=other-model","kb.models.profiles.other.dimension=1024","kb.models.profiles.other.embedding-space=other-space","kb.models.profiles.other.config-version=other-v1",
        "kb.models.profiles.next.enabled=true","kb.models.profiles.next.model-id=qwen-embed","kb.models.profiles.next.provider=bailian",
        "kb.models.profiles.next.model-name=text-embedding-v4","kb.models.profiles.next.dimension=1024","kb.models.profiles.next.embedding-space=bailian-text-embedding-v4","kb.models.profiles.next.config-version=v2"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(DocumentUploadTest.StorageTestConfiguration.class)
@Testcontainers(disabledWithoutDocker=true)
class KnowledgeSearchTest {
    static EmbeddingStub model;
    static synchronized String modelUrl(){if(model==null)model=new EmbeddingStub();return model.url();}
    @AfterAll static void stopModel(){if(model!=null)model.close();}
    @Container static final PostgreSQLContainer<?> DATABASE=new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:0.8.7-pg17-bookworm").asCompatibleSubstituteFor("postgres"));
    @Container static final GenericContainer<?> MINIO=new GenericContainer<>(DockerImageName.parse("minio/minio:RELEASE.2025-09-07T16-13-09Z"))
            .withEnv("MINIO_ROOT_USER","kb-test-access").withEnv("MINIO_ROOT_PASSWORD","kb-test-secret-password")
            .withCommand("server","/data").withExposedPorts(9000).waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));
    @DynamicPropertySource static void infrastructure(DynamicPropertyRegistry properties) {
        properties.add("kb.rag.internal.service-token-hashes.agent-test",()->com.lzj.rag.backend.model.catalog.EmbeddingCatalog.sha256("service-test-token-1234567890-abcdef"));
        properties.add("spring.datasource.url",DATABASE::getJdbcUrl);properties.add("spring.datasource.username",DATABASE::getUsername);properties.add("spring.datasource.password",DATABASE::getPassword);
        properties.add("kb.storage.endpoint",()->"http://"+MINIO.getHost()+":"+MINIO.getMappedPort(9000));properties.add("kb.storage.path-style-access",()->true);
        properties.add("kb.ai.base-url",KnowledgeSearchTest::modelUrl);properties.add("kb.ai.call-timeout",()->"1s");
    }
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired JdbcTemplate jdbc;@Autowired IngestionKernel kernel;@Autowired UserDetailsService users;
    @Autowired DocumentUploadTest.FaultStorage storage;
    @Autowired com.lzj.rag.backend.rag.repository.AnswerLedger answerLedger;
    @org.springframework.boot.test.web.server.LocalServerPort int port;
    @BeforeEach void clear(){model.reset();model.vectorizer=KnowledgeSearchTest::topicVector;storage.reset();TestDatabaseCleanup.clear(jdbc);}
    public static float[] topicVector(String text) {
        String value=text.toLowerCase(Locale.ROOT);float[] vector=new float[1024];
        if(value.contains("backup") || value.contains("pg_dump")){vector[0]=0.8f;vector[1]=0.6f;}
        else if(value.contains("postgres") || value.contains("database") || value.contains("kb_db_url")){vector[0]=1;}
        else if(value.contains("baking") || value.contains("bread")){vector[2]=1;}
        else vector[3]=1;
        return vector;
    }
    private record Dataset(UUID kb,UUID database,UUID backup,UUID cooking) { }
    private Dataset dataset()throws Exception {
        UUID kb=base("alice",null);return new Dataset(kb,document(kb,"database.md",fixture("database.md"),"alice"),document(kb,"backup.md",fixture("backup.md"),"alice"),document(kb,"cooking.md",fixture("cooking.md"),"alice"));
    }
    @Test void returnsRankedStructuredEvidenceFromRealDocumentsAndExportsTheCall()throws Exception {
        var data=dataset();int before=model.requests.size();
        var request=Map.of("query","PostgreSQL connection","kbIds",List.of(data.kb()),"topK",3,"minScore",0.7);
        var response=search(request,"alice",200);var hits=response.path("data");assertThat(hits.size()).isEqualTo(2);
        assertThat(model.requests).hasSize(before+1);assertThat(model.requests.get(before).path("texts").get(0).asText()).isEqualTo("PostgreSQL connection");
        assertThat(hits.get(0).path("documentId").asText()).isEqualTo(data.database().toString());assertThat(hits.get(1).path("documentId").asText()).isEqualTo(data.backup().toString());
        assertThat(hits.get(0).path("score").asDouble()).isCloseTo(1.0,within(0.00001));assertThat(hits.get(1).path("score").asDouble()).isCloseTo(0.8,within(0.00001));
        for(var hit:hits) {
            assertThat(hit.path("kbId").asText()).isEqualTo(data.kb().toString());assertThat(hit.path("chunkIndex").asInt()).isZero();assertThat(hit.path("generation").asInt()).isEqualTo(1);
            assertThat(hit.path("scoreType").asText()).isEqualTo("COSINE_SIMILARITY");
            assertThat(hit.path("content").asText()).isEqualTo(jdbc.queryForObject("SELECT content FROM kb_chunk WHERE id=?",String.class,UUID.fromString(hit.path("chunkId").asText())));
            assertThat(hit.has("embeddingText")).isFalse();assertThat(hit.has("objectKey")).isFalse();
            assertThat(hit.path("fileUrl").asText()).isEqualTo("/api/documents/"+hit.path("documentId").asText()+"/file");
        }
        assertThat(hits.get(0).path("documentName").asText()).isEqualTo("database.md");
        assertThat(hits.get(0).at("/sourceMetadata/chapterPath").toString()).isEqualTo("[\"Operations\",\"Database\"]");
        assertThat(hits.get(0).at("/sourceMetadata/sourceBlocks/0/lineStart").asInt()).isEqualTo(5);assertThat(hits.get(0).path("sourceMetadata").has("pageNumber")).isFalse();
        mvc.perform(get(hits.get(0).path("fileUrl").asText()).with(auth("alice"))).andExpect(status().isOk()).andExpect(content().bytes(fixture("database.md").getBytes(StandardCharsets.UTF_8)));
        Path output=Path.of("target/knowledge-base/search-example.json");Files.createDirectories(output.getParent());
        Files.writeString(output,json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("method","POST","path","/api/knowledge/search","request",request,"response",response,
                "sourceFiles",List.of("src/test/resources/knowledge-base/search/database.md","src/test/resources/knowledge-base/search/backup.md","src/test/resources/knowledge-base/search/cooking.md"),
                "vectorSource","Deterministic local HTTP topic fixture, real S3/PG parsing and cosine SQL; not live semantic model validation")));
    }
    @Test void emptyIndexAndUnmatchedThresholdReturnEmptyArraysWithoutHidingModelErrors()throws Exception {
        UUID empty=base("alice",null);int calls=model.requests.size();
        assertThat(search(Map.of("query","PostgreSQL connection","kbIds",List.of(empty)),"alice",200).path("data").isEmpty()).isTrue();assertThat(model.requests).hasSize(calls);
        var data=dataset();assertThat(search(Map.of("query","astronomy","kbIds",List.of(data.kb()),"minScore",0.9),"alice",200).path("data").isEmpty()).isTrue();
        assertThat(search(Map.of("query","astronomy","kbIds",List.of(data.kb())),"alice",200).path("data").size()).isEqualTo(3); // nearest neighbours are not relevance guarantees
        model.mode="WRONG_DIM";assertThat(search(Map.of("query","database","kbIds",List.of(data.kb())),"alice",502).path("code").asText()).isEqualTo("KB_MODEL_INVALID_RESPONSE");
        model.mode="TIMEOUT";assertThat(search(Map.of("query","database","kbIds",List.of(data.kb())),"alice",504).path("code").asText()).isEqualTo("KB_MODEL_TIMEOUT");
    }
    @Test void structuredRetrievalExposesSafeVersionsAndBothEndpointsPreventResponseCaching()throws Exception {
        var data=dataset();
        var body=json.writeValueAsBytes(Map.of("query","PostgreSQL connection","kbIds",List.of(data.kb()),"minScore",0.7));
        var result=mvc.perform(post("/api/knowledge/retrieve").with(auth("alice")).header("X-Request-Id","rag-trace-test").contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.data.status").value("COMPLETE")).andExpect(jsonPath("$.data.outcome").value("EVIDENCE"))
                .andExpect(jsonPath("$.data.trace.pipelineVersion").value("retrieval-v2"))
                .andExpect(jsonPath("$.data.trace.requestId").value("rag-trace-test"))
                .andExpect(jsonPath("$.data.trace.indexes[0].kbId").value(data.kb().toString())).andReturn();
        var trace=json.readTree(result.getResponse().getContentAsByteArray()).at("/data/trace");
        assertThat(trace.toString()).doesNotContain("tenantId","ownerId","endpoint","token","vectorNamespace");
        assertThat(trace.at("/indexes/0/embeddingConfigRevision").asText()).matches("[0-9a-f]{64}");
        assertThat(trace.at("/indexes/0/aiConfigRevision").asText()).isEqualTo("a".repeat(64));
        mvc.perform(post("/api/knowledge/search").with(auth("alice")).contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(header().string("X-KB-Retrieval-Status","COMPLETE")).andExpect(header().string("X-KB-Retrieval-Outcome","EVIDENCE"))
                .andExpect(jsonPath("$.data").isArray());
    }
    @Test void serverResolvesOnlyOwnedScopesAndExplicitEmptyNeverCallsEmbedding()throws Exception {
        var data=dataset();UUID bob=base("bob",null);document(bob,"private.md","Private database secret","bob");
        var body=Map.of("query","database","strategy","VECTOR");
        var response=mvc.perform(post("/api/knowledge/retrieve").with(auth("alice")).contentType("application/json").content(json.writeValueAsBytes(body)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.strategy").value("VECTOR")).andReturn().getResponse();
        for(var row:json.readTree(response.getContentAsByteArray()).at("/data/evidence"))
            assertThat(row.path("kbId").asText()).isEqualTo(data.kb().toString());
        int calls=model.requests.size();
        mvc.perform(post("/api/knowledge/retrieve").with(auth("alice")).contentType("application/json")
                        .content(json.writeValueAsBytes(Map.of("query","database","kbIds",List.of()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.outcome").value("NO_EVIDENCE"))
                .andExpect(jsonPath("$.data.strategy").value("NONE")).andExpect(jsonPath("$.data.evidence").isEmpty());
        assertThat(search(body,"carol",200).path("data").isEmpty()).isTrue();
        assertThat(model.requests).hasSize(calls);
        base("alice",null);base("alice",null);
        assertThat(search(body,"alice",400).path("code").asText()).isEqualTo("KB_SEARCH_SCOPE_LIMIT");
        search(Map.of("query","database","kbIds",List.of(data.kb(),bob)),"alice",404);
        assertThat(model.requests).hasSize(calls);
    }
    @Test void ragEndpointAssemblesVersionedEvidenceAndDisabledNeighborsCannotEnterTheContext()throws Exception {
        UUID kb=base("alice",null);String text=("Database connection settings. ".repeat(21)+"\n\n").repeat(3);
        UUID doc=document(kb,"long.md",text,"alice");
        var chunks=jdbc.queryForList("SELECT id FROM kb_chunk WHERE document_id=? ORDER BY chunk_index",UUID.class,doc);
        assertThat(chunks).hasSizeGreaterThan(1);
        var request=Map.of("query","database","kbIds",List.of(kb),"topK",2,"strategy","VECTOR");
        var result=mvc.perform(post("/api/rag/retrieve").with(auth("alice")).contentType("application/json").content(json.writeValueAsBytes(request)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("READY")).andExpect(jsonPath("$.data.context.estimated").value(true))
                .andExpect(jsonPath("$.data.context.modelContext").doesNotExist()).andReturn().getResponse();
        var context=json.readTree(result.getContentAsByteArray()).at("/data/context");assertThat(context.path("evidence")).hasSize(2);
        var neighbor=context.path("evidence").get(1);assertThat(neighbor.path("adjacent").asBoolean()).isTrue();assertThat(neighbor.path("rerankScore").isNull()).isTrue();
        assertThat(neighbor.path("evidenceId").asText()).isEqualTo("E2");assertThat(neighbor.path("sourceContentHash").asText()).matches("[0-9a-f]{64}");
        jdbc.update("UPDATE kb_chunk SET enabled=false WHERE id=?",UUID.fromString(neighbor.path("chunkId").asText()));
        var after=mvc.perform(post("/api/rag/retrieve").with(auth("alice")).contentType("application/json").content(json.writeValueAsBytes(request)))
                .andExpect(status().isOk()).andReturn().getResponse();
        for(var source:json.readTree(after.getContentAsByteArray()).at("/data/context/evidence"))
            assertThat(source.path("chunkId").asText()).isNotEqualTo(neighbor.path("chunkId").asText());
        mvc.perform(get(neighbor.path("fileUrl").asText()).with(auth("bob"))).andExpect(status().isNotFound());
        jdbc.update("UPDATE kb_document SET deleted_at=clock_timestamp() WHERE id=?",doc);
        mvc.perform(get(neighbor.path("fileUrl").asText()).with(auth("alice"))).andExpect(status().isNotFound());
        Path out=Path.of("target/knowledge-base/rag-retrieve-example.json");Files.createDirectories(out.getParent());Files.writeString(out,json.writerWithDefaultPrettyPrinter().writeValueAsString(context));
    }
    @Test void standaloneAnswersPersistReferencesAndNeverReadAnotherUsersConversation()throws Exception {
        var data=dataset();
        var request=new LinkedHashMap<String,Object>();request.put("question","database backup");request.put("kbIds",List.of(data.kb()));
        var answer=answer(request,"alice",200).path("data");assertThat(answer.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(answer.path("citationValidation").asText()).isEqualTo("VALID");assertThat(answer.at("/modelCalls/0/promptVersion").asText()).isEqualTo("rag-answer-v1");
        assertThat(answer.at("/modelCalls/0/promptHash").asText()).matches("[0-9a-f]{64}");assertThat(answer.path("citations")).hasSize(1);
        UUID id=UUID.fromString(answer.path("answerId").asText()),conversation=UUID.fromString(answer.path("conversationId").asText());
        assertThat(jdbc.queryForObject("SELECT result_json->>'status' FROM kb_rag_answer_run WHERE id=?",String.class,id)).isEqualTo("COMPLETED");
        for(String user:List.of("bob","carol")) {
            mvc.perform(get("/api/rag/runs/"+id).with(auth(user))).andExpect(status().isNotFound());
            request.put("conversationId",conversation);answer(request,user,404);
        }
        mvc.perform(get("/api/rag/conversations/"+conversation).with(auth("alice"))).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1));
        var modelInput=json.readTree(model.chatRequests.get(0).at("/messages/1/content").asText());
        assertThat(modelInput.path("evidence").isArray()).isTrue();assertThat(model.chatRequests.get(0).at("/messages/0/content").asText()).contains("资料","数据").doesNotContain("pg_dump 备份说明");
        UUID cited=UUID.fromString(answer.at("/citations/0/chunkId").asText());jdbc.update("UPDATE kb_chunk SET enabled=false WHERE id=?",cited);
        mvc.perform(get("/api/rag/runs/"+id).with(auth("alice"))).andExpect(status().isOk()).andExpect(jsonPath("$.data.result.unavailableSources[0]").value("E1"));
        request.put("conversationId",conversation);answer(request,"alice",200);
        var followup=json.readTree(model.chatRequests.get(model.chatRequests.size()-1).at("/messages/1/content").asText());
        for(var message:followup.path("authorizedHistory"))assertThat(message.path("role").asText()).isNotEqualTo("assistant");
    }
    @Test void answerNoEvidenceCitationRepairAndModelFailureHaveDifferentPersistentOutcomes()throws Exception {
        var data=dataset();
        var request=Map.of("question","astronomy","kbIds",List.of(data.kb()),"minScore",.9,"strategy","VECTOR");
        assertThat(answer(request,"alice",200).at("/data/status").asText()).isEqualTo("NO_EVIDENCE");assertThat(model.chatRequests).isEmpty();
        var good=Map.of("question","database backup","kbIds",List.of(data.kb()));
        model.chatMode="REPAIRABLE";var fixed=answer(good,"alice",200).path("data");assertThat(fixed.path("repairAttempted").asBoolean()).isTrue();
        assertThat(fixed.path("status").asText()).isEqualTo("COMPLETED");assertThat(model.chatRequests).hasSize(2);
        model.chatRequests.clear();model.chatMode="INVALID_CITATIONS";var bad=answer(good,"alice",200).path("data");
        assertThat(bad.path("status").asText()).isEqualTo("CITATION_INVALID");assertThat(bad.path("sources")).isNotEmpty();assertThat(bad.path("citations")).isEmpty();assertThat(model.chatRequests).hasSize(2);
        model.chatMode="TIMEOUT";var failed=answer(good,"alice",504).path("data");
        mvc.perform(get("/api/rag/runs/"+failed.path("answerId").asText()).with(auth("alice")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("TIMED_OUT"));
    }
    @Test void conversationConcurrencyAndRevocationDuringGenerationCannotProduceSuccessfulAnswers()throws Exception {
        var data=dataset();var active=answerLedger.begin(actor("alice"),null,"pending","busy-test",java.time.Instant.now().plusSeconds(30));
        answer(Map.of("question","database backup","kbIds",List.of(data.kb()),"conversationId",active.conversationId()),"alice",409);assertThat(model.chatRequests).isEmpty();
        answerLedger.fail(actor("alice"),active.id(),"FAILED","TEST_END","");
        model.beforeChat=()->jdbc.update("UPDATE kb_document SET enabled=false WHERE kb_id=?",data.kb());
        var failed=answer(Map.of("question","database backup","kbIds",List.of(data.kb())),"alice",409).path("data");
        assertThat(jdbc.queryForObject("SELECT status FROM kb_rag_answer_run WHERE id=?",String.class,UUID.fromString(failed.path("answerId").asText()))).isEqualTo("FAILED");
    }
    private JsonNode answer(Map<String,?> request,String user,int expected)throws Exception {
        var response=mvc.perform(post("/api/rag/answer").with(auth(user)).contentType("application/json").content(json.writeValueAsBytes(request)))
                .andExpect(status().is(expected)).andReturn().getResponse();return json.readTree(response.getContentAsByteArray());
    }
    @Test void oneQueryVectorServesCompatibleBasesAndDocumentScopePrecedesRanking()throws Exception {
        var data=dataset();UUID other=base("alice",null);UUID doc=document(other,"database-other.md",fixture("database.md"),"alice");
        var one=search(Map.of("query","database","kbIds",List.of(data.kb())),"alice",200).path("data");assertThat(one.size()).isEqualTo(3);for(var hit:one)assertThat(hit.path("kbId").asText()).isEqualTo(data.kb().toString());
        int before=model.requests.size();
        var selected=search(Map.of("query","database","kbIds",List.of(data.kb(),other),"documentIds",List.of(data.backup(),doc)),"alice",200).path("data");
        assertThat(model.requests).hasSize(before+1);assertThat(selected.size()).isEqualTo(2);assertThat(selected.get(0).path("documentId").asText()).isEqualTo(doc.toString());
        assertThat(selected.get(1).path("documentId").asText()).isEqualTo(data.backup().toString());
        assertThat(search(Map.of("query","database","kbIds",List.of(data.kb()),"documentIds",List.of(doc)),"alice",400).path("code").asText()).isEqualTo("KB_SEARCH_DOCUMENT_SCOPE");
        search(Map.of("query","database","kbIds",List.of(data.kb()),"documentIds",List.of(UUID.randomUUID())),"alice",404);
        UUID empty=base("alice",null);assertThat(search(Map.of("query","database","kbIds",List.of(empty,data.kb())),"alice",200).path("data").size()).isEqualTo(3);
    }
    @Test void authorizationCoversEveryRequestedBaseAndDocumentBeforeCallingTheModel()throws Exception {
        var data=dataset();UUID bob=base("bob",null);UUID secret=document(bob,"private.txt","Private database connection evidence.","bob");int before=model.requests.size();
        for(String user:List.of("bob","carol")) {
            search(Map.of("query","database","kbIds",List.of(data.kb())),user,404);
            mvc.perform(get("/api/documents/"+data.database()+"/file").with(auth(user))).andExpect(status().isNotFound());
        }
        search(Map.of("query","database","kbIds",List.of(data.kb(),bob)),"alice",404);
        search(Map.of("query","database","kbIds",List.of(data.kb()),"documentIds",List.of(secret)),"alice",404);
        mvc.perform(post("/api/knowledge/search").contentType("application/json").content(json.writeValueAsString(Map.of("query","database","kbIds",List.of(data.kb()))))).andExpect(status().isUnauthorized());
        assertThat(model.requests).hasSize(before);
    }
    @Test void validatesConfiguredLimitsDefaultsAndStableTies()throws Exception {
        UUID kb=base("alice",null);for(int i=0;i<7;i++)document(kb,"database-"+i+".txt","Database connection evidence "+i,"alice");
        var request=new LinkedHashMap<String,Object>();request.put("query","database");request.put("kbIds",List.of(kb));
        var defaultHits=search(request,"alice",200).path("data");assertThat(defaultHits.size()).isEqualTo(5);
        var ids=new ArrayList<String>();for(var hit:defaultHits)ids.add(hit.path("chunkId").asText());assertThat(ids).isSorted();
        assertThat(search(request,"alice",200).path("data")).isEqualTo(defaultHits);
        request.put("topK",6);assertThat(search(request,"alice",200).path("data").size()).isEqualTo(6);
        int before=model.requests.size();
        for(Object value:List.of(0,7,1.5,"5")){request.put("topK",value);search(request,"alice",400);}request.remove("topK");
        for(Object value:List.of(-1.01,1.01,"NaN")){request.put("minScore",value);search(request,"alice",400);}request.remove("minScore");
        for(String value:List.of(" ","x".repeat(257),"bad\u0000query")){request.put("query",value);search(request,"alice",400);}request.put("query","database");
        request.put("kbIds",List.of(kb,kb));search(request,"alice",400);request.put("kbIds",List.of(kb,UUID.randomUUID(),UUID.randomUUID()));search(request,"alice",400);
        request.put("kbIds",List.of(kb));request.put("documentIds",List.of());search(request,"alice",400);request.put("documentIds",List.of(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID()));search(request,"alice",400);
        request.remove("documentIds");request.put("namespace","fake");search(request,"alice",400);
        assertThat(model.requests).hasSize(before);
    }
    @Test void disabledAndDeletedCandidatesAreFilteredBeforeLimitAndChunkDisablingStaysIndependent()throws Exception {
        UUID kb=base("alice",null);var docs=new ArrayList<UUID>();for(int i=0;i<8;i++)docs.add(document(kb,"database-"+i+".txt","Database evidence "+i,"alice"));
        jdbc.update("UPDATE kb_chunk SET enabled=false WHERE document_id=?",docs.get(0));jdbc.update("UPDATE kb_document SET enabled=false WHERE id IN (?,?)",docs.get(0),docs.get(1));
        jdbc.update("UPDATE kb_document SET deleted_at=clock_timestamp() WHERE id=?",docs.get(2));
        var hits=search(Map.of("query","database","kbIds",List.of(kb)),"alice",200).path("data");assertThat(hits.size()).isEqualTo(5);
        for(var hit:hits)assertThat(UUID.fromString(hit.path("documentId").asText())).isNotIn(docs.subList(0,3));
        jdbc.update("UPDATE kb_document SET enabled=true WHERE id=?",docs.get(0));
        assertThat(search(Map.of("query","database","kbIds",List.of(kb),"documentIds",List.of(docs.get(0))),"alice",200).path("data").isEmpty()).isTrue();
    }
    @Test void generationSwitchDuringQueryEmbeddingUsesTheNewGenerationAndCurrentDocumentName()throws Exception {
        var data=dataset();var once=new java.util.concurrent.atomic.AtomicBoolean();
        model.beforeEmbed=()->{if(once.compareAndSet(false,true)) {
            jdbc.update("UPDATE kb_document SET name='Renamed database' WHERE id=?",data.database());kernel.ingest(actor("alice"),data.database());
        }};
        var hits=search(Map.of("query","database","kbIds",List.of(data.kb()),"documentIds",List.of(data.database())),"alice",200).path("data");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kb_generation WHERE document_id=?",Integer.class,data.database())).isEqualTo(2);
        assertThat(hits.size()).isEqualTo(1);assertThat(hits.get(0).path("generation").asInt()).isEqualTo(2);assertThat(hits.get(0).path("documentName").asText()).isEqualTo("Renamed database");
    }
    @Test void deletionOrDisablingDuringEmbeddingIsRecheckedByTheFinalQuery()throws Exception {
        var data=dataset();model.beforeEmbed=()->jdbc.update("UPDATE kb_document SET enabled=false WHERE id=?",data.database());
        assertThat(search(Map.of("query","database","kbIds",List.of(data.kb()),"documentIds",List.of(data.database())),"alice",200).path("data").isEmpty()).isTrue();
        model.beforeEmbed=()->jdbc.update("UPDATE kb_knowledge_base SET deleted_at=clock_timestamp() WHERE id=?",data.kb());
        search(Map.of("query","database","kbIds",List.of(data.kb())),"alice",404);
    }
    @Test void rejectsDifferentModelsOrLocalRevisionsEvenWithTheSameDimensionBeforeEmbedding()throws Exception {
        var data=dataset();UUID other=base("alice","other"),next=base("alice","next");int before=model.requests.size();
        for(UUID incompatible:List.of(other,next)) {
            assertThat(jdbc.queryForObject("SELECT embedding_dimension FROM kb_knowledge_base WHERE id=?",Integer.class,incompatible)).isEqualTo(1024);
            assertThat(search(Map.of("query","database","kbIds",List.of(data.kb(),incompatible)),"alice",409).path("code").asText()).isEqualTo("KB_SEARCH_MODEL_MISMATCH");
        }
        assertThat(model.requests).hasSize(before);
    }
    @Test void pinnedAiRevisionIsPartOfTheSpaceAndCannotDriftDuringSearch()throws Exception {
        var data=dataset();UUID other=base("alice",null);model.revision="b".repeat(64);document(other,"database-b.md",fixture("database.md"),"alice");int before=model.requests.size();
        assertThat(search(Map.of("query","database","kbIds",List.of(data.kb(),other)),"alice",409).path("code").asText()).isEqualTo("KB_SEARCH_MODEL_MISMATCH");
        assertThat(search(Map.of("query","database","kbIds",List.of(data.kb())),"alice",409).path("code").asText()).isEqualTo("KB_MODEL_CONFIG_CHANGED");assertThat(model.requests).hasSize(before);
        assertThat(search(Map.of("query","database","kbIds",List.of(other)),"alice",200).path("data").size()).isEqualTo(1);
    }
    @Test void sourceMetadataOmitsStorageAndProviderDetails()throws Exception {
        var data=dataset();jdbc.update("""
                UPDATE kb_chunk SET metadata_json=metadata_json || '{"objectKey":"private-object-key","providerUrl":"private-provider","sourceBlocks":[{"lineStart":5,"lineEnd":5,"accessKey":"private-credential"}]}'::jsonb
                WHERE document_id=?
                """,data.database());
        var response=search(Map.of("query","database","kbIds",List.of(data.kb()),"documentIds",List.of(data.database())),"alice",200);
        assertThat(response.toString()).doesNotContain("private-object-key","private-provider","private-credential","accessKey","objectKey");
        assertThat(response.at("/data/0/sourceMetadata/sourceBlocks/0/lineStart").asInt()).isEqualTo(5);
    }
    @Test void realSseDeliversIncrementalTextAndPersistsOnlyTheCompletedReferenceSet()throws Exception {
        var data=dataset();var release=new java.util.concurrent.CountDownLatch(1);
        model.beforeStreamTail=()->{try{if(!release.await(4,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("fixture gate timed out");}catch(InterruptedException e){Thread.currentThread().interrupt();}};
        try(var stream=stream(Map.of("question","PostgreSQL backup","kbIds",List.of(data.kb()),"strategy","VECTOR"))) {
            var meta=stream.next();assertThat(meta.name()).isEqualTo("meta");UUID run=UUID.fromString(meta.data().path("runId").asText());
            assertThat(stream.next().name()).isEqualTo("retrieval");var sources=stream.next();assertThat(sources.name()).isEqualTo("sources");
            assertThat(sources.data().at("/evidence/0/evidenceId").asText()).isEqualTo("E1");
            var first=stream.next();assertThat(first.name()).isEqualTo("message");assertThat(first.data().path("text").asText()).contains("pg_dump");
            assertThat(answerLedger.get(actor("alice"),run).status()).isEqualTo("RUNNING");release.countDown();
            var events=stream.rest();assertThat(events.stream().map(SseEvent::name).toList()).containsExactly("message","message","finish","done");
            var finish=events.get(2).data();assertThat(finish.path("persisted").asBoolean()).isTrue();assertThat(finish.path("status").asText()).isEqualTo("COMPLETED");
            assertThat(finish.at("/run/result/citationValidation").asText()).isEqualTo("VALID");assertThat(finish.at("/run/result/citations/0/evidenceId").asText()).isEqualTo("E1");
            assertThat(finish.at("/run/result/usage/totalTokens").asInt()).isEqualTo(42);
            assertThat(answerLedger.get(actor("alice"),run).answerText()).isEqualTo("备份可使用 pg_dump。[E1]");
            assertThat(events.toString()).doesNotContain("private reasoning","private upstream endpoint");
        } finally {release.countDown();}
    }
    @Test void realSseDistinguishesNoEvidenceInvalidCitationsBrokenUpstreamsAndTimeouts()throws Exception {
        var data=dataset();
        try(var stream=stream(Map.of("question","PostgreSQL backup","kbIds",List.of(),"strategy","VECTOR"))) {
            var events=stream.rest();assertThat(events.stream().map(SseEvent::name).toList()).containsExactly("meta","retrieval","sources","message","finish","done");
            assertThat(events.get(4).data().path("status").asText()).isEqualTo("NO_EVIDENCE");assertThat(model.streamRequests).isEmpty();
        }
        model.mode="RATE_LIMIT";
        try(var stream=stream(Map.of("question","PostgreSQL backup","kbIds",List.of(data.kb()),"strategy","VECTOR"))) {
            var events=stream.rest();assertThat(events.stream().map(SseEvent::name).toList()).containsExactly("meta","retrieval","error","finish","done");
            assertThat(events.get(1).data().path("status").asText()).isEqualTo("UNAVAILABLE");assertThat(events.get(3).data().path("status").asText()).isEqualTo("FAILED");assertThat(model.streamRequests).isEmpty();
        } finally {model.mode="GOOD";}
        for(String scenario:List.of("INVALID_REFERENCE","DANGLING_REFERENCE","INCOMPLETE","ERROR","IDLE")) {
            model.streamMode=scenario;int before=model.streamRequests.size();
            try(var stream=stream(Map.of("question","PostgreSQL backup","kbIds",List.of(data.kb()),"strategy","VECTOR"))) {
                var events=stream.rest();var names=events.stream().map(SseEvent::name).toList();assertThat(names.subList(names.size()-3,names.size())).containsExactly("error","finish","done");
                var finish=events.get(events.size()-2).data();String expected=scenario.endsWith("REFERENCE")?"CITATION_INVALID":scenario.equals("IDLE")?"TIMED_OUT":"FAILED";
                assertThat(finish.path("status").asText()).isEqualTo(expected);assertThat(finish.path("persisted").asBoolean()).isTrue();
                UUID run=UUID.fromString(finish.at("/run/answerId").asText());assertThat(answerLedger.get(actor("alice"),run).status()).isEqualTo(expected);
                assertThat(finish.at("/run/answer").asText()).contains("pg_dump");assertThat(finish.at("/run/result/repairAttempted").asBoolean()).isFalse();
                assertThat(events.toString()).doesNotContain("private upstream endpoint","private reasoning");
            }
            assertThat(model.streamRequests).hasSize(before+1);
        }
    }
    @Test void realSseCancelAndDisconnectCloseUpstreamAndExpiredRunsRecoverWithoutReplay()throws Exception {
        var data=dataset();model.streamMode="HANG";
        try(var stream=stream(Map.of("question","PostgreSQL backup","kbIds",List.of(data.kb()),"strategy","VECTOR"))) {
            var meta=stream.next().data();UUID run=UUID.fromString(meta.path("runId").asText());UUID conversation=UUID.fromString(meta.path("conversationId").asText());
            while(!stream.next().name().equals("message")){}
            for(String user:List.of("bob","carol"))mvc.perform(post("/api/rag/runs/"+run+"/cancel").with(auth(user))).andExpect(status().isNotFound());
            mvc.perform(post("/api/rag/answer").with(auth("alice")).contentType("application/json").content(json.writeValueAsString(Map.of("question","backup","conversationId",conversation,"kbIds",List.of(data.kb()))))).andExpect(status().isConflict());
            mvc.perform(post("/api/rag/runs/"+run+"/cancel").with(auth("alice"))).andExpect(status().isOk());
            var events=stream.rest();assertThat(events.stream().filter(e->e.name().equals("finish")).toList()).hasSize(1);
            assertThat(events.stream().filter(e->e.name().equals("finish")).findFirst().orElseThrow().data().path("status").asText()).isEqualTo("CANCELLED");
            awaitCondition(()->model.streamClosed.get()>=1);assertThat(answerLedger.get(actor("alice"),run).answerText()).contains("pg_dump");
            assertThat(answerLedger.get(actor("alice"),run).result().query().effectiveQuery()).contains("PostgreSQL");
            assertThat(answerLedger.get(actor("alice"),run).result().modelCalls().get(0).model()).isEqualTo("qwen-plus-fixture");
            mvc.perform(post("/api/rag/runs/"+run+"/cancel").with(auth("alice"))).andExpect(status().isOk());
        }
        int before=model.streamClosed.get();UUID disconnected;
        try(var stream=stream(Map.of("question","PostgreSQL backup","kbIds",List.of(data.kb()),"strategy","VECTOR"))) {
            disconnected=UUID.fromString(stream.next().data().path("runId").asText());while(!stream.next().name().equals("message")){}
        }
        awaitCondition(()->answerLedger.get(actor("alice"),disconnected).status().equals("CANCELLED"));awaitCondition(()->model.streamClosed.get()>before);
        assertThat(answerLedger.get(actor("alice"),disconnected).answerText()).contains("pg_dump");
        int closedBefore=model.streamClosed.get();
        try(var stream=stream(Map.of("question","PostgreSQL backup","kbIds",List.of(data.kb()),"strategy","VECTOR"))) {
            UUID expired=UUID.fromString(stream.next().data().path("runId").asText());while(!stream.next().name().equals("message")){}
            jdbc.update("UPDATE kb_rag_answer_run SET deadline_at=clock_timestamp()-interval '1 second' WHERE id=?",expired);
            var events=stream.rest();assertThat(events.stream().filter(e->e.name().equals("finish")).findFirst().orElseThrow().data().path("status").asText()).isEqualTo("TIMED_OUT");
            awaitCondition(()->model.streamClosed.get()>closedBefore);assertThat(answerLedger.get(actor("alice"),expired).answerText()).contains("pg_dump");
        }
        var orphan=answerLedger.begin(actor("alice"),null,"orphan","orphan-fixture",java.time.Instant.now().minusSeconds(1));
        awaitCondition(()->answerLedger.get(actor("alice"),orphan.id()).status().equals("TIMED_OUT"));
    }
    @Test void internalToolUsesUserDelegationCurrentScopeAndIndependentTraceAcrossHttp()throws Exception {
        var data=dataset();UUID other=base("alice",null);var grant=grant(data.kb(),List.of(data.backup()));String token=grant.path("delegationToken").asText();
        String trace="00-0123456789abcdef0123456789abcdef-0123456789abcdef-01";
        try(var client=new InternalClient()) {
            var response=client.call("/internal/rag/retrieve",Map.of("query","PostgreSQL backup","strategy","VECTOR"),token,trace,UUID.randomUUID().toString(),200);
            assertThat(response.at("/data/status").asText()).isEqualTo("READY");assertThat(response.at("/data/evidence/0/documentId").asText()).isEqualTo(data.backup().toString());
            assertThat(response.at("/data/traceId").asText()).isEqualTo("0123456789abcdef0123456789abcdef");
            assertThat(model.traceparents).anyMatch(t->t.startsWith("00-0123456789abcdef0123456789abcdef-"));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM kb_rag_conversation",Integer.class)).isZero();
            assertThat(response.toString()).doesNotContain("modelContext","originalQuery","objectKey","providerUrl","allowedKbIds");
            client.call("/internal/rag/retrieve",Map.of("query","backup","kbIds",List.of(other)),token,trace,UUID.randomUUID().toString(),403);
            UUID bob=base("bob",null);
            client.call("/internal/rag/retrieve",Map.of("query","backup","kbIds",List.of(bob)),token,trace,UUID.randomUUID().toString(),403);
            client.call("/internal/rag/retrieve",Map.of("query","backup","documentIds",List.of(data.database())),token,trace,UUID.randomUUID().toString(),403);
            client.call("/internal/rag/retrieve",Map.of("query","backup","conversationId",UUID.randomUUID()),token,trace,UUID.randomUUID().toString(),400);
            client.call("/internal/rag/retrieve",Map.of("query","backup"),token,"00-"+"0".repeat(32)+"-0123456789abcdef-01",UUID.randomUUID().toString(),400);
            jdbc.update("UPDATE kb_chunk SET enabled=false WHERE document_id=?",data.backup());
            assertThat(client.call("/internal/rag/retrieve",Map.of("query","backup"),token,trace,UUID.randomUUID().toString(),200).at("/data/status").asText()).isEqualTo("NO_EVIDENCE");
            mvc.perform(delete("/api/rag/delegations/"+grant.path("delegationId").asText()).with(auth("bob"))).andExpect(status().isNotFound());
            mvc.perform(delete("/api/rag/delegations/"+grant.path("delegationId").asText()).with(auth("alice"))).andExpect(status().isNoContent());
            client.call("/internal/rag/retrieve",Map.of("query","backup"),token,trace,UUID.randomUUID().toString(),401);
        }
        mvc.perform(post("/internal/rag/retrieve").with(auth("alice")).contentType("application/json").content("{\"query\":\"backup\"}")).andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/rag/retrieve").header("X-Rag-Service","agent-test").header("Authorization","Bearer service-test-token-1234567890-abcdef")
                .header("X-Request-Id",UUID.randomUUID()).contentType("application/json").content("{\"query\":\"backup\"}")).andExpect(status().isBadRequest());
    }
    @Test void internalToolCancellationBeforeAndDuringRecallNeverProducesEvidenceAndFailuresStayUnavailable()throws Exception {
        var data=dataset();String token=grant(data.kb(),null).path("delegationToken").asText();String trace="00-1123456789abcdef0123456789abcdef-1123456789abcdef-01";
        try(var client=new InternalClient()) {
            String cancelled=UUID.randomUUID().toString();
            client.call("/internal/rag/requests/"+cancelled+"/cancel",Map.of(),token,trace,UUID.randomUUID().toString(),202);
            int count=model.requests.size();client.call("/internal/rag/retrieve",Map.of("query","backup"),token,trace,cancelled,409);assertThat(model.requests).hasSize(count);
            model.mode="TIMEOUT";var entered=new java.util.concurrent.CountDownLatch(1);model.beforeEmbed=entered::countDown;
            String active=UUID.randomUUID().toString();
            var request=java.util.concurrent.CompletableFuture.supplyAsync(()->{try{return client.call("/internal/rag/retrieve",Map.of("query","backup"),token,trace,active,503);}catch(Exception e){throw new RuntimeException(e);}});
            assertThat(entered.await(3,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            client.call("/internal/rag/requests/"+active+"/cancel",Map.of(),token,trace,UUID.randomUUID().toString(),202);
            assertThat(request.get(3,java.util.concurrent.TimeUnit.SECONDS).at("/data/status").asText()).isEqualTo("UNAVAILABLE");
            assertThat(jdbc.queryForObject("SELECT status FROM kb_rag_tool_request WHERE request_id=?",String.class,active)).isEqualTo("CANCELLED");
            model.mode="RATE_LIMIT";model.beforeEmbed=()->{};
            var failed=client.call("/internal/rag/retrieve",Map.of("query","backup"),token,trace,UUID.randomUUID().toString(),503);
            assertThat(failed.at("/data/status").asText()).isEqualTo("UNAVAILABLE");assertThat(failed.toString()).doesNotContain("private upstream","jdbc:","SELECT ");
        }
    }
    private JsonNode grant(UUID kb,List<UUID> docs)throws Exception {
        var input=new LinkedHashMap<String,Object>();input.put("serviceId","agent-test");input.put("agentSessionId",UUID.randomUUID());input.put("kbIds",List.of(kb));if(docs!=null)input.put("documentIds",docs);
        var response=mvc.perform(post("/api/rag/delegations").with(auth("alice")).contentType("application/json").content(json.writeValueAsString(input)))
                .andExpect(status().isCreated()).andReturn().getResponse();return json.readTree(response.getContentAsByteArray()).path("data");
    }
    @Test @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="kb.test.python-exe",matches=".+")
    void pythonAdapterCallsRunningJavaEvidenceServiceWithUniqueReferencesAndSharedTrace()throws Exception {
        var data=dataset();var grant=grant(data.kb(),null);
        String trace="2123456789abcdef0123456789abcdef";
        var config=Map.of("origin","http://127.0.0.1:"+port,"serviceId","agent-test","serviceToken","service-test-token-1234567890-abcdef","grant",grant,"traceId",trace);
        var process=new ProcessBuilder(System.getProperty("kb.test.python-exe"),"-X","utf8",System.getProperty("kb.test.rag-adapter"))
                .redirectErrorStream(true).start();
        try(var stdin=process.getOutputStream()){stdin.write(json.writeValueAsBytes(config));}
        assertThat(process.waitFor(30,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        String output=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
        assertThat(process.exitValue()).as(output).isZero();JsonNode receipt=json.readTree(output);
        assertThat(receipt.path("statuses").toString()).isEqualTo("[\"READY\",\"READY\"]");assertThat(receipt.path("traceId").asText()).isEqualTo(trace);
        assertThat(receipt.path("uniqueCitationIds").get(0).asText()).isNotEqualTo(receipt.path("uniqueCitationIds").get(1).asText());
        assertThat(receipt.path("extractiveCitationValidated").asBoolean()).isTrue();
        assertThat(model.traceparents).anyMatch(value->value.startsWith("00-"+trace+"-"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kb_rag_conversation",Integer.class)).isZero();
        Path receiptFile=Path.of("target/knowledge-base/agent-tool-contract.json");Files.createDirectories(receiptFile.getParent());Files.writeString(receiptFile,json.writerWithDefaultPrettyPrinter().writeValueAsString(receipt));
    }
    @Test @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="kb.test.agent-python",matches=".+")
    void pythonMainAgentRunsGraphAgainstJavaWithUserDelegationAndValidatedSources()throws Exception {
        var data=dataset();String trace="3123456789abcdef0123456789abcdef";
        var config=new LinkedHashMap<String,Object>();
        config.put("origin","http://127.0.0.1:"+port);config.put("serviceId","agent-test");
        config.put("serviceToken","service-test-token-1234567890-abcdef");
        config.put("username","alice");config.put("password","alice-local-test-password");
        config.put("kbIds",List.of(data.kb()));config.put("traceId",trace);
        config.put("stateDir",Path.of("target/knowledge-base/agent-state-"+UUID.randomUUID()).toAbsolutePath().toString());
        var process=new ProcessBuilder(System.getProperty("kb.test.agent-python"),"-X","utf8",System.getProperty("kb.test.agent-smoke"))
                .redirectErrorStream(false).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        try {
            try(var stdin=process.getOutputStream()){stdin.write(json.writeValueAsBytes(config));}
            assertThat(process.waitFor(45,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            String output=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
            assertThat(process.exitValue()).as(output).isZero();JsonNode receipt=json.readTree(output);
            assertThat(receipt.path("status").asText()).isEqualTo("COMPLETED");
            assertThat(receipt.path("retrievalStatuses").toString()).isEqualTo("[\"READY\",\"READY\"]");
            assertThat(receipt.path("traceId").asText()).isEqualTo(trace);
            assertThat(receipt.path("modelRequests").asInt()).isEqualTo(2);
            assertThat(model.traceparents).anyMatch(value->value.startsWith("00-"+trace+"-"));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM kb_rag_conversation",Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM kb_rag_delegation WHERE revoked_at IS NOT NULL",Integer.class)).isGreaterThanOrEqualTo(4);
            assertThat(receipt.at("/conversationPersistence/partialSavedWhileRunning").asBoolean()).isTrue();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM kb_agent_message",Integer.class)).isEqualTo(6);
            Path receiptFile=Path.of("target/knowledge-base/agent-runtime-contract.json");Files.createDirectories(receiptFile.getParent());
            Files.writeString(receiptFile,json.writerWithDefaultPrettyPrinter().writeValueAsString(receipt));
        } finally {if(process.isAlive())process.destroyForcibly();}
    }
    @Test void agentConversationHistoryIsOwnedOrderedIdempotentAndNeverReexecutes()throws Exception {
        var create=Map.of("requestId",UUID.randomUUID());
        var c=agentPublic("POST","/api/conversations",create,"alice",201).path("data");String id=c.path("id").asText();
        assertThat(agentPublic("GET","/api/conversations/"+id+"/messages",null,"alice",200).at("/data/nextBeforeSequence").asLong()).isZero();
        assertThat(agentPublic("POST","/api/conversations",create,"alice",201).at("/data/id").asText()).isEqualTo(id);
        for(String user:List.of("bob","carol")) {
            assertThat(agentPublic("GET","/api/conversations",null,user,200).at("/data/total").asInt()).isZero();
            agentPublic("GET","/api/conversations/"+id+"/messages",null,user,404);
            agentPublic("PATCH","/api/conversations/"+id,Map.of("title","入侵"),user,404);
            agentPublic("POST","/api/conversations/"+id+"/delegations",Map.of("serviceId","agent-test"),user,404);
        }
        String grant=agentPublic("POST","/api/conversations/"+id+"/delegations",Map.of("serviceId","agent-test"),"alice",201).at("/data/delegationToken").asText();
        String root="/internal/agent/conversations/"+id;
        var begin=Map.of("requestId",UUID.randomUUID(),"messageId",UUID.randomUUID(),"question","测试问题","deadline",java.time.Instant.now().plusSeconds(30));
        agentInternal("POST",root+"/runs",begin,null,401);
        var run=agentInternal("POST",root+"/runs",begin,grant,200).path("data");String runId=run.path("runId").asText();
        assertThat(run.path("created").asBoolean()).isTrue();
        var repeat=agentInternal("POST",root+"/runs",begin,grant,200).path("data");
        assertThat(repeat.path("created").asBoolean()).isFalse();assertThat(repeat.path("runId").asText()).isEqualTo(runId);
        var wrong=new LinkedHashMap<String,Object>(begin);wrong.put("question","其他问题");agentInternal("POST",root+"/runs",wrong,grant,409);
        wrong.put("requestId",UUID.randomUUID());wrong.put("messageId",UUID.randomUUID());agentInternal("POST",root+"/runs",wrong,grant,409);
        var partial=agentSnapshot(1,"RUNNING","已生成的部分内容",List.of(),List.of());
        agentInternal("PATCH",root+"/runs/"+runId,partial,grant,200);
        agentInternal("PATCH",root+"/runs/"+runId,partial,grant,200);
        agentInternal("PATCH",root+"/runs/"+runId,agentSnapshot(1,"RUNNING","冲突文本",List.of(),List.of()),grant,409);
        agentPublic("POST","/api/conversations/"+id+"/runs/"+runId+"/cancel",Map.of(),"bob",404);
        agentPublic("POST","/api/conversations/"+id+"/runs/"+runId+"/cancel",Map.of(),"alice",200);
        var late=agentInternal("PATCH",root+"/runs/"+runId,agentSnapshot(2,"COMPLETED","已生成的部分内容。",List.of(),List.of()),grant,200);
        assertThat(late.at("/data/status").asText()).isEqualTo("CANCELLED");
        var latest=agentPublic("GET","/api/conversations/"+id+"/messages?size=1",null,"alice",200).path("data");
        assertThat(latest.path("records").get(0).path("content").asText()).isEqualTo("已生成的部分内容。");assertThat(latest.path("hasMore").asBoolean()).isTrue();
        var older=agentPublic("GET","/api/conversations/"+id+"/messages?size=1&beforeSequence="+latest.path("nextBeforeSequence").asLong(),null,"alice",200);
        assertThat(older.at("/data/records/0/role").asText()).isEqualTo("user");
        var next=agentInternal("POST",root+"/runs",wrong,grant,200).path("data");
        var abandoned=new LinkedHashMap<String,Object>(agentSnapshot(1,"RUNNING","等待工具",List.of(),List.of()));
        abandoned.put("tools",List.of(Map.of("toolCallId","call-abandoned","name","search_knowledge","status","RUNNING")));
        agentInternal("PATCH",root+"/runs/"+next.path("runId").asText(),abandoned,grant,200);
        jdbc.update("UPDATE kb_agent_run SET deadline_at=clock_timestamp()-interval '1 second' WHERE id=?",UUID.fromString(next.path("runId").asText()));
        assertThat(agentPublic("GET","/api/conversations/"+id+"/runs/"+next.path("runId").asText(),null,"alice",200).at("/data/run/status").asText()).isEqualTo("TIMED_OUT");
        assertThat(agentPublic("GET","/api/conversations/"+id+"/runs/"+next.path("runId").asText(),null,"alice",200).at("/data/message/tools/0/status").asText()).isEqualTo("UNAVAILABLE");
        assertThat(agentInternal("POST",root+"/runs",wrong,grant,200).at("/data/created").asBoolean()).isFalse();
        agentPublic("PATCH","/api/conversations/"+id,Map.of("title","重命名后的对话"),"alice",200);
        assertThat(agentPublic("GET","/api/conversations?search=重命名",null,"alice",200).at("/data/records/0/title").asText()).isEqualTo("重命名后的对话");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kb_agent_message WHERE conversation_id=?",Integer.class,UUID.fromString(id))).isEqualTo(4);
        agentPublic("DELETE","/api/conversations/"+id,null,"alice",204);
        agentPublic("GET","/api/conversations/"+id+"/messages",null,"alice",404);
        agentInternal("POST",root+"/runs",wrong,grant,404);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kb_agent_message",Integer.class)).isZero();
    }
    @Test void agentBlocksKeepOrderAndRejectSnapshotRewrites()throws Exception {
        String id=agentPublic("POST","/api/conversations",Map.of("requestId",UUID.randomUUID()),"alice",201).at("/data/id").asText();
        String grant=agentPublic("POST","/api/conversations/"+id+"/delegations",Map.of("serviceId","agent-test"),"alice",201).at("/data/delegationToken").asText();
        String root="/internal/agent/conversations/"+id;
        String runId=agentInternal("POST",root+"/runs",Map.of("requestId",UUID.randomUUID(),"messageId",UUID.randomUUID(),"question","查询资料","deadline",java.time.Instant.now().plusSeconds(30)),grant,200).at("/data/runId").asText();
        var answer=new LinkedHashMap<String,Object>(Map.of("blockId","B"+"a".repeat(32),"kind","answer","text","先查询资料。","status","streaming"));
        var tool=new LinkedHashMap<String,Object>(Map.of("blockId","B"+"b".repeat(32),"kind","tool","toolCallId","call-one","name","search_knowledge","status","pending","batchId","B"+"c".repeat(32),"callIndex",0,"arguments",Map.of("query","资料"),"evidenceIds",List.of()));
        var checkpoint=new LinkedHashMap<String,Object>(agentSnapshot(1,"RUNNING","先查询资料。",List.of(),List.of()));
        checkpoint.put("blocks",List.of(answer,tool));checkpoint.put("eventSeq",3);
        checkpoint.put("tools",List.of(Map.of("toolCallId","call-one","name","search_knowledge","status","RUNNING")));
        agentInternal("PATCH",root+"/runs/"+runId,checkpoint,grant,200);
        answer.put("status","done");tool.put("status","running");tool.put("startedAt",java.time.Instant.now());
        checkpoint.put("sequence",2);checkpoint.put("eventSeq",4);
        agentInternal("PATCH",root+"/runs/"+runId,checkpoint,grant,200);
        checkpoint.put("sequence",3);checkpoint.put("eventSeq",5);checkpoint.put("blocks",List.of(tool,answer));
        agentInternal("PATCH",root+"/runs/"+runId,checkpoint,grant,409);
        checkpoint.put("blocks",List.of(answer,tool));checkpoint.put("eventSeq",2);
        agentInternal("PATCH",root+"/runs/"+runId,checkpoint,grant,409);
        var finalText=Map.of("blockId","B"+"d".repeat(32),"kind","answer","status","done","text","当前资料不足。");
        tool.put("status","done");tool.put("durationMs",12);
        checkpoint.put("blocks",List.of(answer,tool,finalText));checkpoint.put("eventSeq",7);
        checkpoint.put("content","先查询资料。\n\n当前资料不足。");checkpoint.put("status","COMPLETED");
        checkpoint.put("tools",List.of(Map.of("toolCallId","call-one","name","search_knowledge","status","NO_EVIDENCE")));
        agentInternal("PATCH",root+"/runs/"+runId,checkpoint,grant,200);
        var message=agentPublic("GET","/api/conversations/"+id+"/messages",null,"alice",200).at("/data/records/1");
        assertThat(message.path("blocks").size()).isEqualTo(3);
        assertThat(message.at("/blocks/1/toolCallId").asText()).isEqualTo("call-one");
        assertThat(message.at("/blocks/1/result/evidence").isArray()).isTrue();
        assertThat(message.path("eventSeq").asInt()).isEqualTo(7);
    }
    @Test void agentHistorySnapshotsAreReauthorizedBeforeDisplayAndModelHistory()throws Exception {
        var data=dataset();String id=agentPublic("POST","/api/conversations",Map.of("requestId",UUID.randomUUID(),"kbIds",List.of(data.kb())),"alice",201).at("/data/id").asText();
        String grant=agentPublic("POST","/api/conversations/"+id+"/delegations",Map.of("serviceId","agent-test"),"alice",201).at("/data/delegationToken").asText();
        String root="/internal/agent/conversations/"+id;
        JsonNode found;
        try(var client=new InternalClient()){found=client.call("/internal/rag/retrieve",Map.of("query","PostgreSQL backup","kbIds",List.of(data.kb()),"topK",1),grant,"00-"+"1".repeat(32)+"-1234567890abcdef-01","history-evidence",200).path("data");}
        var source=(com.fasterxml.jackson.databind.node.ObjectNode)found.path("evidence").get(0).deepCopy();
        String ref="K"+"a".repeat(32)+"_E1";source.put("evidenceId",ref);source.set("versions",found.path("versions"));
        for(String key:List.of("chunkIndex","documentName","fileUrl","adjacent","parentChunkId","subQueries","fusionScore","rerankScore"))source.remove(key);
        source.put("toolTruncated",false);
        var run=agentInternal("POST",root+"/runs",Map.of("requestId",UUID.randomUUID(),"messageId",UUID.randomUUID(),"question","备份步骤","deadline",java.time.Instant.now().plusSeconds(30)),grant,200).path("data");
        var snapshot=new LinkedHashMap<String,Object>(agentSnapshot(1,"COMPLETED","使用 pg_dump。["+ref+"]",List.of(source),List.of(ref)));
        snapshot.put("blocks",List.of(Map.of("blockId","B"+"a".repeat(32),"kind","tool","toolCallId","call-source","name","search_knowledge","status","done","batchId","B"+"c".repeat(32),"callIndex",0,"evidenceIds",List.of(ref),"startedAt",java.time.Instant.now(),"durationMs",10),
                Map.of("blockId","B"+"b".repeat(32),"kind","answer","status","done","text","使用 pg_dump。["+ref+"]")));
        snapshot.put("eventSeq",4);snapshot.put("tools",List.of(Map.of("toolCallId","call-source","name","search_knowledge","status","READY")));
        agentInternal("PATCH",root+"/runs/"+run.path("runId").asText(),snapshot,grant,200);
        var history=agentPublic("GET","/api/conversations/"+id+"/messages",null,"alice",200).at("/data/records/1");
        assertThat(history.at("/citations/0/content").asText()).isNotBlank();assertThat(history.at("/evidence/0/versions/indexes").isArray()).isTrue();
        assertThat(history.at("/blocks/0/result/evidence/0/content").asText()).isNotBlank();
        var derived=agentInternal("POST",root+"/runs",Map.of("requestId",UUID.randomUUID(),"messageId",UUID.randomUUID(),"question","继续说明","deadline",java.time.Instant.now().plusSeconds(30)),grant,200).path("data");
        agentInternal("PATCH",root+"/runs/"+derived.path("runId").asText(),agentSnapshot(1,"COMPLETED","沿用先前的备份说明。",List.of(source),List.of()),grant,200);
        UUID chunk=UUID.fromString(source.path("chunkId").asText());jdbc.update("UPDATE kb_chunk SET enabled=false WHERE id=?",chunk);
        var hidden=agentPublic("GET","/api/conversations/"+id+"/messages",null,"alice",200).at("/data/records/1");
        assertThat(hidden.path("sourcesRestricted").asBoolean()).isTrue();assertThat(hidden.path("content").asText()).doesNotContain("pg_dump");assertThat(hidden.path("evidence").isEmpty()).isTrue();
        assertThat(hidden.path("blocks").isEmpty()).isTrue();
        assertThat(agentPublic("GET","/api/conversations/"+id+"/messages",null,"alice",200).at("/data/records/3/sourcesRestricted").asBoolean()).isTrue();
        assertThat(agentInternal("GET",root+"/history",null,grant,200).path("data").size()).isEqualTo(2);
        var turns=agentInternal("GET",root+"/history?view=turns&runId="+derived.path("runId").asText(),null,grant,200).path("data");
        assertThat(turns.at("/turns/0/sourcesRestricted").asBoolean()).isTrue();
        assertThat(turns.at("/turns/0/assistant").isNull()).isTrue();
        assertThat(turns.toString()).doesNotContain("pg_dump");
        assertThat(jdbc.queryForObject("SELECT evidence::text FROM kb_agent_message WHERE id=?",String.class,UUID.fromString(run.path("assistantMessageId").asText()))).contains("pg_dump");
    }
    @Test void agentContextHistoryPagesWholeRunsAtFixedOwnedBoundary()throws Exception {
        String id=agentPublic("POST","/api/conversations",Map.of("requestId",UUID.randomUUID()),"alice",201).at("/data/id").asText();
        String grant=agentPublic("POST","/api/conversations/"+id+"/delegations",Map.of("serviceId","agent-test"),"alice",201).at("/data/delegationToken").asText();
        String root="/internal/agent/conversations/"+id;
        var runs=new ArrayList<String>();
        for(int i=0;i<4;i++) {
            var run=agentInternal("POST",root+"/runs",Map.of("requestId",UUID.randomUUID(),"messageId",UUID.randomUUID(),"question","问题"+i,"deadline",java.time.Instant.now().plusSeconds(30)),grant,200).path("data");
            String runId=run.path("runId").asText();runs.add(runId);
            agentInternal("PATCH",root+"/runs/"+runId,agentSnapshot(1,i==1?"FAILED":"COMPLETED","答复"+i,List.of(),List.of()),grant,200);
        }
        String query=root+"/history?view=turns&runId="+runs.get(2)+"&size=1";
        agentInternal("GET",query,null,null,401);
        var page=agentInternal("GET",query,null,grant,200).path("data");
        assertThat(page.path("upperSequence").asLong()).isEqualTo(5);
        assertThat(page.path("hasMore").asBoolean()).isTrue();
        assertThat(page.at("/turns/0/runId").asText()).isEqualTo(runs.get(1));
        assertThat(page.at("/turns/0/user/content").asText()).isEqualTo("问题1");
        assertThat(page.at("/turns/0/assistant").isNull()).isTrue();
        var older=agentInternal("GET",query+"&beforeSequence="+page.path("nextBeforeSequence").asLong(),null,grant,200).path("data");
        assertThat(older.path("hasMore").asBoolean()).isFalse();
        assertThat(older.at("/turns/0/user/content").asText()).isEqualTo("问题0");
        assertThat(older.at("/turns/0/assistant/content").asText()).isEqualTo("答复0");
        assertThat(older.at("/turns/0/assistant/sequence").asLong()).isEqualTo(2);
        assertThat(agentInternal("GET",query+"&beforeSequence=999",null,grant,200).at("/data/upperSequence").asLong()).isEqualTo(5);
        agentInternal("GET",root+"/history?view=turns",null,grant,400);
        agentInternal("GET",query.replace("size=1","size=9"),null,grant,400);
        String other=agentPublic("POST","/api/conversations",Map.of("requestId",UUID.randomUUID()),"bob",201).at("/data/id").asText();
        String otherGrant=agentPublic("POST","/api/conversations/"+other+"/delegations",Map.of("serviceId","agent-test"),"bob",201).at("/data/delegationToken").asText();
        agentInternal("GET",query,null,otherGrant,404);
        agentInternal("GET",root+"/history?view=turns&runId="+UUID.randomUUID(),null,grant,404);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kb_agent_message WHERE conversation_id=?",Integer.class,UUID.fromString(id))).isEqualTo(8);
    }
    @Test void agentSummaryCommitReuseAndDerivedAnswersKeepAuthorizationLineage()throws Exception {
        var data=dataset();
        String id=agentPublic("POST","/api/conversations",Map.of("requestId",UUID.randomUUID(),"kbIds",List.of(data.kb())),"alice",201).at("/data/id").asText();
        String grant=agentPublic("POST","/api/conversations/"+id+"/delegations",Map.of("serviceId","agent-test"),"alice",201).at("/data/delegationToken").asText();
        String root="/internal/agent/conversations/"+id;
        JsonNode found;
        try(var client=new InternalClient()){found=client.call("/internal/rag/retrieve",Map.of("query","PostgreSQL backup","kbIds",List.of(data.kb()),"topK",1),grant,"00-"+"1".repeat(32)+"-1234567890abcdef-01","summary-source",200).path("data");}
        var source=(com.fasterxml.jackson.databind.node.ObjectNode)found.path("evidence").get(0).deepCopy();
        source.put("evidenceId","K"+"a".repeat(32)+"_E1");source.set("versions",found.path("versions"));source.put("toolTruncated",false);
        for(String key:List.of("chunkIndex","documentName","fileUrl","adjacent","parentChunkId","subQueries","fusionScore","rerankScore"))source.remove(key);
        var runs=new ArrayList<JsonNode>();
        for(int i=0;i<3;i++) {
            var run=agentInternal("POST",root+"/runs",Map.of("requestId",UUID.randomUUID(),"messageId",UUID.randomUUID(),"question",i==0?"先不要提交，编号 A-42":"继续"+i,"deadline",java.time.Instant.now().plusSeconds(60)),grant,200).path("data");
            runs.add(run);
            if(i<2)agentInternal("PATCH",root+"/runs/"+run.path("runId").asText(),agentSnapshot(1,"COMPLETED",i==0?"历史答复：使用 pg_dump。":"近期答复",i==0?List.of(source):List.of(),List.of()),grant,200);
        }
        String active=runs.get(2).path("runId").asText();String query=root+"/context-summary?runId="+active+"&retainedTurns=1&policyVersion=summary-test-v1";
        agentInternal("GET",query,null,null,401);
        var prepared=agentInternal("GET",query,null,grant,200).path("data");
        assertThat(prepared.path("turns").size()).isEqualTo(1);assertThat(prepared.path("throughSequence").asLong()).isEqualTo(2);
        String userId=runs.get(0).path("userMessageId").asText(),assistantId=runs.get(0).path("assistantMessageId").asText();
        var content=Map.of("goals",List.of(Map.of("text","备份方案 A-42","sourceMessageIds",List.of(userId))),
                "constraints",List.of(Map.of("text","先不要提交，编号 A-42","sourceMessageIds",List.of(userId))),
                "facts",List.of(Map.of("text","历史答复指出使用 pg_dump","sourceMessageIds",List.of(assistantId))),"openQuestions",List.of());
        var write=new LinkedHashMap<String,Object>(Map.of("runId",active,"expectedRevision",0,"retainedTurns",1,"sourceHash",prepared.path("sourceHash").asText(),
                "content",content,"model","fixture-model","promptVersion","context-summary-v1","promptHash","a".repeat(64),"policyVersion","summary-test-v1"));
        var bad=new LinkedHashMap<String,Object>(write);bad.put("sourceHash","b".repeat(64));agentInternal("PUT",root+"/context-summary",bad,grant,409);
        bad.put("sourceHash",prepared.path("sourceHash").asText());bad.put("content",Map.of("goals",List.of(Map.of("text","冒充来源","sourceMessageIds",List.of(UUID.randomUUID()))),"constraints",List.of(),"facts",List.of(),"openQuestions",List.of()));
        agentInternal("PUT",root+"/context-summary",bad,grant,409);
        UUID chunk=UUID.fromString(source.path("chunkId").asText());
        jdbc.update("UPDATE kb_chunk SET enabled=false WHERE id=?",chunk);
        // 模拟摘要模型调用期间撤权：不能提交旧来源，也不推进水位。
        agentInternal("PUT",root+"/context-summary",write,grant,409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kb_agent_context_summary",Integer.class)).isZero();
        jdbc.update("UPDATE kb_chunk SET enabled=true WHERE id=?",chunk);
        agentInternal("PUT",root+"/context-summary",write,grant,200);
        agentInternal("PUT",root+"/context-summary",write,grant,409);
        var available=agentInternal("GET",query,null,grant,200).path("data");
        assertThat(available.path("caughtUp").asBoolean()).isTrue();assertThat(available.at("/summary/content/constraints/0/text").asText()).contains("A-42");
        assertThat(agentInternal("GET",query.replace("summary-test-v1","summary-test-v2"),null,grant,200).at("/data/summary").isNull()).isTrue();
        String other=agentPublic("POST","/api/conversations",Map.of("requestId",UUID.randomUUID()),"bob",201).at("/data/id").asText();
        String otherGrant=agentPublic("POST","/api/conversations/"+other+"/delegations",Map.of("serviceId","agent-test"),"bob",201).at("/data/delegationToken").asText();
        agentInternal("GET",query,null,otherGrant,404);
        agentInternal("PUT","/internal/agent/conversations/"+other+"/context-summary",write,otherGrant,404);
        agentInternal("POST",root+"/context-summary/use",Map.of("runId",active,"revision",1,"retainedTurns",1,"policyVersion","summary-test-v1"),grant,200);
        // 派生回答不能通过省略依赖绕过以后撤权。
        agentInternal("PATCH",root+"/runs/"+active,agentSnapshot(1,"COMPLETED","沿用摘要继续说明",List.of(),List.of()),grant,409);
        agentInternal("PATCH",root+"/runs/"+active,agentSnapshot(1,"COMPLETED","沿用摘要继续说明",List.of(source),List.of()),grant,200);
        jdbc.update("UPDATE kb_chunk SET enabled=false WHERE id=?",chunk);
        var messages=agentPublic("GET","/api/conversations/"+id+"/messages",null,"alice",200).path("data").path("records");
        assertThat(messages.get(5).path("sourcesRestricted").asBoolean()).isTrue();assertThat(messages.get(5).path("content").asText()).doesNotContain("沿用摘要");
        var next=agentInternal("POST",root+"/runs",Map.of("requestId",UUID.randomUUID(),"messageId",UUID.randomUUID(),"question","重新提问","deadline",java.time.Instant.now().plusSeconds(30)),grant,200).path("data");
        var invalidated=agentInternal("GET",query.replace(active,next.path("runId").asText()),null,grant,200).path("data");
        assertThat(invalidated.path("summary").isNull()).isTrue();assertThat(invalidated.toString()).doesNotContain("pg_dump");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kb_agent_message WHERE conversation_id=?",Integer.class,UUID.fromString(id))).isEqualTo(8);
        agentPublic("DELETE","/api/conversations/"+id,null,"alice",204);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kb_agent_context_summary WHERE conversation_id=?",Integer.class,UUID.fromString(id))).isZero();
    }
    @Test void userPreferencesAreIsolatedVersionedAndBoundToIdempotentRuns()throws Exception {
        String path="/api/agent/preferences";
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        assertThat(agentPublic("GET",path,null,"alice",200).at("/data/revision").asLong()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kb_agent_preferences",Integer.class)).isZero();
        var write=Map.of("expectedRevision",0,"language","EN","detail","CONCISE","format","BULLETS");
        var selected=agentPublic("PUT",path,write,"alice",200).path("data");
        assertThat(selected.path("revision").asLong()).isEqualTo(1);assertThat(selected.path("language").asText()).isEqualTo("EN");
        assertThat(agentPublic("GET",path,null,"bob",200).at("/data/revision").asLong()).isZero();
        var actor=(com.lzj.rag.backend.security.model.KnowledgeBasePrincipal)users.loadUserByUsername("alice");
        var otherTenant=new com.lzj.rag.backend.security.model.KnowledgeBasePrincipal("same-user-other-tenant","unused","different-tenant",actor.userId());
        mvc.perform(put(path).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(otherTenant)).contentType("application/json").content(json.writeValueAsBytes(write)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.revision").value(1));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kb_agent_preferences WHERE owner_id=?",Integer.class,actor.userId())).isEqualTo(2);
        assertThat(agentPublic("GET",path,null,"alice",200).at("/data/revision").asLong()).isEqualTo(1);
        agentPublic("PUT",path,Map.of("expectedRevision",1,"language","IGNORE_RULES","detail","CONCISE","format","AUTO"),"alice",400);
        agentPublic("POST",path+"/reset",Map.of(),"alice",400);
        String conversation=agentPublic("POST","/api/conversations",Map.of("requestId",UUID.randomUUID()),"alice",201).at("/data/id").asText();
        String grant=agentPublic("POST","/api/conversations/"+conversation+"/delegations",Map.of("serviceId","agent-test"),"alice",201).at("/data/delegationToken").asText();
        String root="/internal/agent/conversations/"+conversation;
        var begin=Map.of("requestId",UUID.randomUUID(),"messageId",UUID.randomUUID(),"question","偏好测试","deadline",java.time.Instant.now().plusSeconds(60));
        var run=agentInternal("POST",root+"/runs",begin,grant,200).path("data");
        assertThat(run.at("/preferences/language").asText()).isEqualTo("EN");
        agentPublic("PUT",path,Map.of("expectedRevision",1,"language","ZH_CN","detail","DETAILED","format","PARAGRAPHS"),"alice",200);
        var replay=agentInternal("POST",root+"/runs",begin,grant,200).path("data");
        assertThat(replay.path("created").asBoolean()).isFalse();assertThat(replay.path("preferences")).isEqualTo(run.path("preferences"));
        String runId=run.path("runId").asText();
        assertThat(agentPublic("GET","/api/conversations/"+conversation+"/runs/"+runId,null,"alice",200).at("/data/run/preferences/revision").asLong()).isEqualTo(1);
        agentInternal("PATCH",root+"/runs/"+runId,agentSnapshot(1,"COMPLETED","完成",List.of(),List.of()),grant,200);
        var reset=agentPublic("POST",path+"/reset",Map.of("expectedRevision",2),"alice",200).path("data");
        assertThat(reset.path("revision").asLong()).isEqualTo(3);assertThat(reset.path("epoch").asLong()).isEqualTo(1);assertThat(reset.path("language").asText()).isEqualTo("AUTO");
        agentPublic("PUT",path,write,"alice",409); // 清空前的旧页面不能恢复旧设置。
        var next=agentInternal("POST",root+"/runs",Map.of("requestId",UUID.randomUUID(),"messageId",UUID.randomUUID(),"question","新的回答","deadline",java.time.Instant.now().plusSeconds(30)),grant,200).path("data");
        assertThat(next.at("/preferences/revision").asLong()).isEqualTo(3);assertThat(next.at("/preferences/epoch").asLong()).isEqualTo(1);
        assertThat(next.at("/preferences/detail").asText()).isEqualTo("BALANCED");
        agentPublic("DELETE","/api/conversations/"+conversation,null,"alice",204);
        assertThat(agentPublic("GET",path,null,"alice",200).at("/data/revision").asLong()).isEqualTo(3);
    }
    private Map<String,Object> agentSnapshot(long sequence,String status,String text,List<?> evidence,List<String> citations) {
        return Map.of("sequence",sequence,"status",status,"content",text,"evidence",evidence,"citations",citations,"tools",List.of(),"model","fixture-model","promptVersion","test-v1","promptHash","a".repeat(64));
    }
    private JsonNode agentPublic(String method,String path,Object body,String user,int expected)throws Exception {
        var request=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(org.springframework.http.HttpMethod.valueOf(method),path).with(auth(user));
        if(body!=null)request.contentType("application/json").content(json.writeValueAsBytes(body));
        var response=mvc.perform(request).andReturn().getResponse();assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(expected);
        return response.getContentAsByteArray().length==0?json.createObjectNode():json.readTree(response.getContentAsByteArray());
    }
    private JsonNode agentInternal(String method,String path,Object body,String grant,int expected)throws Exception {
        var request=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(org.springframework.http.HttpMethod.valueOf(method),path)
                .header("Authorization","Bearer service-test-token-1234567890-abcdef").header("X-Rag-Service","agent-test")
                .header("X-Rag-Deadline",java.time.Instant.now().plusSeconds(5).toString()).header("traceparent","00-"+"1".repeat(32)+"-1234567890abcdef-01");
        if(grant!=null)request.header("X-Rag-Delegation",grant);if(body!=null)request.contentType("application/json").content(json.writeValueAsBytes(body));
        var response=mvc.perform(request).andReturn().getResponse();assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(expected);return json.readTree(response.getContentAsByteArray());
    }
    private final class InternalClient implements AutoCloseable {
        final okhttp3.OkHttpClient client=new okhttp3.OkHttpClient.Builder().readTimeout(java.time.Duration.ofSeconds(8)).build();
        JsonNode call(String path,Map<String,?> body,String token,String trace,String requestId,int expected)throws Exception {
            var request=new okhttp3.Request.Builder().url("http://127.0.0.1:"+port+path).header("Authorization","Bearer service-test-token-1234567890-abcdef")
                    .header("X-Rag-Service","agent-test").header("X-Rag-Delegation",token).header("X-Request-Id",requestId).header("traceparent",trace)
                    .header("X-Rag-Deadline",java.time.Instant.now().plusSeconds(5).toString()).post(okhttp3.RequestBody.create(json.writeValueAsBytes(body),okhttp3.MediaType.get("application/json"))).build();
            try(var response=client.newCall(request).execute()){String text=response.body().string();assertThat(response.code()).as(text).isEqualTo(expected);return json.readTree(text);}
        }
        public void close(){client.dispatcher().executorService().shutdownNow();client.connectionPool().evictAll();}
    }
    private void awaitCondition(java.util.function.BooleanSupplier condition)throws Exception {
        long end=System.nanoTime()+java.time.Duration.ofSeconds(5).toNanos();while(!condition.getAsBoolean() && System.nanoTime()<end)Thread.sleep(40);assertThat(condition.getAsBoolean()).isTrue();
    }
    private record SseEvent(String name,JsonNode data) {}
    private SseSession stream(Map<String,?> body)throws Exception {
        var client=new okhttp3.OkHttpClient.Builder().readTimeout(java.time.Duration.ofSeconds(10)).build();
        var call=client.newCall(new okhttp3.Request.Builder().url("http://127.0.0.1:"+port+"/api/rag/answer/stream")
                .header("Authorization",okhttp3.Credentials.basic("alice","alice-local-test-password"))
                .post(okhttp3.RequestBody.create(json.writeValueAsBytes(body),okhttp3.MediaType.get("application/json"))).build());
        var response=call.execute();assertThat(response.code()).isEqualTo(200);assertThat(response.header("Cache-Control")).isEqualTo("no-store");return new SseSession(client,call,response);
    }
    private final class SseSession implements AutoCloseable {
        final okhttp3.OkHttpClient client;final okhttp3.Call call;final okhttp3.Response response;
        SseSession(okhttp3.OkHttpClient client,okhttp3.Call call,okhttp3.Response response){this.client=client;this.call=call;this.response=response;}
        SseEvent next()throws Exception {
            String type=null;StringBuilder data=new StringBuilder();String line;
            while((line=response.body().source().readUtf8Line())!=null) {
                if(line.isEmpty() && type!=null)return new SseEvent(type,json.readTree(data.toString()));
                if(line.startsWith("event:"))type=line.substring(6).strip();else if(line.startsWith("data:"))data.append(line.substring(5).stripLeading());
            }
            throw new AssertionError("SSE ended before done");
        }
        List<SseEvent> rest()throws Exception {var events=new ArrayList<SseEvent>();SseEvent event;do{event=next();events.add(event);}while(!event.name().equals("done"));return events;}
        public void close(){call.cancel();response.close();client.dispatcher().executorService().shutdownNow();client.connectionPool().evictAll();}
    }
    private UUID base(String user,String profile)throws Exception {
        var body=new LinkedHashMap<String,Object>();body.put("name","Search "+UUID.randomUUID());if(profile!=null)body.put("embeddingProfileId",profile);
        var response=mvc.perform(post("/api/kbs").with(auth(user)).contentType("application/json").content(json.writeValueAsString(body))).andExpect(status().isCreated()).andReturn().getResponse();
        return UUID.fromString(json.readTree(response.getContentAsByteArray()).at("/data/id").asText());
    }
    private UUID document(UUID kb,String filename,String source,String user)throws Exception {
        var response=mvc.perform(multipart("/api/kbs/"+kb+"/documents").file(new MockMultipartFile("file",filename,"text/plain",source.getBytes(StandardCharsets.UTF_8)))
                .header("Idempotency-Key","search-upload-"+UUID.randomUUID()).with(auth(user))).andExpect(status().isCreated()).andReturn().getResponse();
        UUID id=UUID.fromString(json.readTree(response.getContentAsByteArray()).at("/data/id").asText());kernel.ingest(actor(user),id);return id;
    }
    private JsonNode search(Map<String,?> body,String user,int expected)throws Exception {
        var response=mvc.perform(post("/api/knowledge/search").with(auth(user)).contentType("application/json").content(json.writeValueAsString(body)))
                .andExpect(status().is(expected)).andReturn().getResponse();
        return json.readTree(response.getContentAsByteArray());
    }
    private String fixture(String file)throws Exception {try(var stream=getClass().getResourceAsStream("/knowledge-base/search/"+file)){return new String(stream.readAllBytes(),StandardCharsets.UTF_8);}}
    private KnowledgeBasePrincipal actor(String user){return (KnowledgeBasePrincipal)users.loadUserByUsername(user);}
    private static org.springframework.test.web.servlet.request.RequestPostProcessor auth(String user){return httpBasic(user,user+"-local-test-password");}
}
