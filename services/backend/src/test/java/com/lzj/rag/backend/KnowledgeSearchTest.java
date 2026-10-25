package com.lzj.rag.backend;

import com.fasterxml.jackson.databind.*;
import com.lzj.rag.backend.model.EmbeddingStub;
import com.lzj.rag.backend.ingestion.IngestionKernel;
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

/** Actual S3 uploads, parsing, HTTP query embeddings and pgvector SQL. Vectors are an explicit local topic fixture. */
@SpringBootTest(properties={"kb.storage.enabled=true","kb.storage.bucket=kb-search-test","kb.storage.max-upload-bytes=4096",
        "kb.search.max-top-k=6","kb.search.max-kb-ids=2","kb.search.max-document-ids=3","kb.search.max-query-chars=256",
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
        properties.add("spring.datasource.url",DATABASE::getJdbcUrl);properties.add("spring.datasource.username",DATABASE::getUsername);properties.add("spring.datasource.password",DATABASE::getPassword);
        properties.add("kb.storage.endpoint",()->"http://"+MINIO.getHost()+":"+MINIO.getMappedPort(9000));properties.add("kb.storage.path-style-access",()->true);
        properties.add("kb.ai.base-url",KnowledgeSearchTest::modelUrl);properties.add("kb.ai.call-timeout",()->"1s");
    }
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired JdbcTemplate jdbc;@Autowired IngestionKernel kernel;@Autowired UserDetailsService users;
    @Autowired DocumentUploadTest.FaultStorage storage;
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
