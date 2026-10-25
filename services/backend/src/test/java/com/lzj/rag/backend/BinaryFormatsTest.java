package com.lzj.rag.backend;

import com.fasterxml.jackson.databind.*;
import com.lzj.rag.backend.ingestion.*;
import com.lzj.rag.backend.knowledgebase.KnowledgeBaseException;
import com.lzj.rag.backend.model.*;
import com.lzj.rag.backend.parser.*;
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
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"kb.storage.enabled=true","kb.storage.bucket=kb-formats-test","kb.storage.max-upload-bytes=4194304",
        "kb.formats.mineru.enabled=true","kb.formats.mineru.timeout=1s","kb.formats.vlm.enabled=true"})
@AutoConfigureMockMvc @ActiveProfiles("test") @Import(DocumentUploadTest.StorageTestConfiguration.class)
@Testcontainers(disabledWithoutDocker=true)
class BinaryFormatsTest {
    static EmbeddingStub model;static MineruStub mineru;static Map<String,byte[]> files;
    static synchronized String modelUrl(){if(model==null)model=new EmbeddingStub();return model.url();}
    static synchronized String mineruUrl(){if(mineru==null)mineru=new MineruStub();return mineru.url();}
    @BeforeAll static void fixtures()throws Exception {files=FormatFixtures.loadOrCreate();var directory=Path.of("target/knowledge-base/formats");Files.createDirectories(directory);for(var entry:files.entrySet())Files.write(directory.resolve(entry.getKey()),entry.getValue());}
    @AfterAll static void close(){if(model!=null)model.close();if(mineru!=null)mineru.close();}
    @Container static final PostgreSQLContainer<?> DATABASE=new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:0.8.7-pg17-bookworm").asCompatibleSubstituteFor("postgres"));
    @Container static final GenericContainer<?> MINIO=new GenericContainer<>(DockerImageName.parse("minio/minio:RELEASE.2025-09-07T16-13-09Z"))
            .withEnv("MINIO_ROOT_USER","kb-test-access").withEnv("MINIO_ROOT_PASSWORD","kb-test-secret-password")
            .withCommand("server","/data").withExposedPorts(9000).waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",DATABASE::getJdbcUrl);p.add("spring.datasource.username",DATABASE::getUsername);p.add("spring.datasource.password",DATABASE::getPassword);
        p.add("kb.storage.endpoint",()->"http://"+MINIO.getHost()+":"+MINIO.getMappedPort(9000));p.add("kb.storage.path-style-access",()->true);
        p.add("kb.storage.temp-directory",()->Path.of("target/knowledge-base/format-spool").toAbsolutePath().toString());
        p.add("kb.ai.base-url",BinaryFormatsTest::modelUrl);p.add("kb.formats.mineru.base-url",BinaryFormatsTest::mineruUrl);
    }
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired JdbcTemplate jdbc;@Autowired IngestionKernel kernel;@Autowired UserDetailsService users;
    @Autowired ParserRegistry parsers;@Autowired LocalFormatParser local;@Autowired FormatProperties config;
    @Autowired IngestionJobService jobs;@Autowired ParserSpoolCleanup spoolCleanup;
    @Autowired DocumentUploadTest.FaultStorage storage;
    @BeforeEach void clear(){model.reset();mineru.mode="GOOD";mineru.multipart=null;storage.reset();TestDatabaseCleanup.clear(jdbc);}
    private KnowledgeBasePrincipal actor(){return (KnowledgeBasePrincipal)users.loadUserByUsername("alice");}
    private UUID base()throws Exception {
        var response=mvc.perform(post("/api/kbs").with(httpBasic("alice","alice-local-test-password")).contentType("application/json").content("{\"name\":\"formats\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse();return UUID.fromString(json.readTree(response.getContentAsByteArray()).at("/data/id").asText());
    }
    private UUID upload(UUID kb,String filename,byte[] content,String profile,int status)throws Exception {
        var metadata=Map.of("ingestionSpec",Map.of("parseProfile",profile,"splitter",Map.of("tableRows",1)));
        var request=multipart("/api/kbs/"+kb+"/documents").file(new MockMultipartFile("file",filename,"application/octet-stream",content))
                .file(new MockMultipartFile("metadata","","application/json",json.writeValueAsBytes(metadata))).header("Idempotency-Key",UUID.randomUUID().toString());
        var response=mvc.perform(request.with(httpBasic("alice","alice-local-test-password"))).andExpect(status().is(status)).andReturn().getResponse();
        return status<300?UUID.fromString(json.readTree(response.getContentAsByteArray()).at("/data/id").asText()):null;
    }
    private List<Map<String,Object>> rows(UUID doc){return jdbc.queryForList("SELECT id,content,embedding_text,metadata_json::text AS metadata FROM kb_chunk WHERE document_id=? ORDER BY chunk_index",doc);}
    @AfterEach void noSpoolLeaks()throws Exception {
        var directory=Path.of("target/knowledge-base/format-spool");if(Files.exists(directory))try(var paths=Files.list(directory)){assertThat(paths.toList()).as("Verified binary and parser request/result spools are reclaimed").isEmpty();}
    }
    @Test void allImplementedFastFormatsUseTheSameKernelAndPersistTheirRealSources()throws Exception {
        UUID kb=base();var evidence=new LinkedHashMap<String,Object>();
        for(String name:List.of("ordinary.pdf","handbook.docx","slides.pptx","legacy.ppt","multi-sheet.xls","multi-sheet.xlsx","quoted.csv","invoice.png","invoice.jpeg")) {
            UUID doc=upload(kb,name,files.get(name),"fast",201);var result=kernel.ingest(actor(),doc);assertThat(result.chunks()).isNotEmpty();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM kb_vector WHERE document_id=?",Integer.class,doc)).isEqualTo(result.chunks().size());
            mvc.perform(get("/api/documents/"+doc+"/file").with(httpBasic("alice","alice-local-test-password"))).andExpect(status().isOk()).andExpect(content().bytes(files.get(name)));
            var chunks=rows(doc);evidence.put(name,chunks);String all=json.writeValueAsString(chunks);
            assertThat(all).doesNotContain("base64","objectKey","data:image","img_path");
            if(name.equals("ordinary.pdf"))assertThat(all).contains("pageNumber", "PostgreSQL", "Backup");
            if(name.equals("handbook.docx")) {
                assertThat(all).contains("PostgreSQL","苹果","项目");
                assertThat(json.readTree(chunks.get(0).get("metadata").toString()).path("chapterPath").toString()).contains("Database handbook");
            }
            if(name.endsWith("pptx"))assertThat(all).contains("slideNumber","slide two");
            if(name.startsWith("multi-sheet")) {
                assertThat(chunks).hasSize(4);assertThat(all).contains("销售","Stock","3.00","7.50","空行之后","rowNumbers");
                assertThat(chunks.get(0).get("embedding_text").toString()).contains("名称：苹果","总额：3.00","备注：");
                var last=json.readTree(chunks.get(2).get("metadata").toString());assertThat(last.path("rowStart").asInt()).isEqualTo(5);assertThat(last.path("rowEnd").asInt()).isEqualTo(5);
                for(var row:chunks.subList(0,3))assertThat(row.get("content").toString()).contains("名称","数量","总额");
            }
            if(name.equals("quoted.csv")) {
                assertThat(chunks).hasSize(3);assertThat(chunks.get(0).get("content").toString()).contains("第一行<br>第二行");
                assertThat(chunks.get(1).get("embedding_text").toString()).contains("数量：；备注：中文空值");
                assertThat(chunks.get(2).get("embedding_text").toString()).contains("=SUM(1,2)");
            }
            if(name.startsWith("invoice"))assertThat(all).contains("VLM_DESCRIPTION_AND_OCR","INVOICE 42").doesNotContain("lineStart","pageNumber");
        }
        Files.writeString(Path.of("target/knowledge-base/format-chunks.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("note","Actual local parsing/PG/S3; image descriptions are an HTTP fixture, not live OCR", "formats",evidence)));
        assertThat(model.visionRequests).hasSize(2);var request=model.visionRequests.get(0);
        assertThat(request.path("preferredModel").asText()).isEqualTo("qwen-vision");assertThat(request.at("/messages/0/images/0/mediaType").asText()).isEqualTo("image/png");
        assertThat(Base64.getDecoder().decode(request.at("/messages/0/images/0/base64").asText())).isEqualTo(files.get("invoice.png"));
    }
    @Test void scannedPdfFastExplicitlyFailsAndFidelityUsesVersionedProtocolWithoutDowngrade()throws Exception {
        UUID doc=upload(base(),"scanned.pdf",files.get("scanned.pdf"),"fast",201);
        assertThatThrownBy(()->kernel.ingest(actor(),doc)).isInstanceOfSatisfying(KnowledgeBaseException.class,e->assertThat(e.code()).isEqualTo("KB_OCR_REQUIRED"));
        assertThat(mineru.multipart).isNull();
        mvc.perform(patch("/api/documents/"+doc).with(httpBasic("alice","alice-local-test-password")).contentType("application/json")
                .content("{\"version\":1,\"ingestionSpec\":{\"parseProfile\":\"fidelity\",\"splitter\":{\"tableRows\":1}}}")).andExpect(status().isOk());
        var result=kernel.ingest(actor(),doc);assertThat(result.chunks()).hasSize(3);
        assertThat(result.chunks().get(0).content()).contains("INVOICE 42");
        assertThat(result.chunks().get(1).embeddingText()).contains("项目：苹果","数量：2");assertThat(result.chunks().get(2).embeddingText()).contains("项目：苹果","数量：3");
        assertThat(json.writeValueAsString(result.chunks())).contains("pageNumber","bbox","MINERU_2_7_6").doesNotContain("table_body","base64");
        assertThat(mineru.multipart).contains("name=\"files\"; filename=\"document.pdf\"","%PDF-","name=\"return_content_list\"\r\n\r\ntrue","name=\"return_images\"\r\n\r\nfalse");
    }
    @Test void externalProtocolFailuresAndTimeoutKeepPriorPublishedGeneration()throws Exception {
        UUID doc=upload(base(),"ordinary.pdf",files.get("ordinary.pdf"),"fast",201);kernel.ingest(actor(),doc);
        mvc.perform(patch("/api/documents/"+doc).with(httpBasic("alice","alice-local-test-password")).contentType("application/json").content("{\"version\":1,\"ingestionSpec\":{\"parseProfile\":\"fidelity\"}}" )).andExpect(status().isOk());
        for(String mode:List.of("WRONG_API","VERSION","UNAVAILABLE","TIMEOUT","EMPTY","BAD_SPAN")) {
            mineru.mode=mode;assertThatThrownBy(()->kernel.ingest(actor(),doc)).isInstanceOf(KnowledgeBaseException.class);
            assertThat(jdbc.queryForObject("SELECT active_generation FROM kb_document WHERE id=?",Long.class,doc)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM kb_generation WHERE document_id=?",Integer.class,doc)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT error_message FROM kb_ingestion_job WHERE document_id=? ORDER BY target_generation DESC LIMIT 1",String.class,doc)).doesNotContain("private upstream secret");
        }
    }
    @Test void formulaEvaluationAndCsvShapeErrorsArePermanentAndNeverSilentEmptyValues()throws Exception {
        UUID kb=base();
        try(var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook();var bytes=new java.io.ByteArrayOutputStream()) {
            var s=book.createSheet("Errors");s.createRow(0).createCell(0).setCellValue("Result");s.createRow(1).createCell(0).setCellFormula("1/0");book.write(bytes);
            UUID doc=upload(kb,"error.xlsx",bytes.toByteArray(),"fast",201);
            assertThatThrownBy(()->kernel.ingest(actor(),doc)).isInstanceOfSatisfying(KnowledgeBaseException.class,e->assertThat(e.code()).isEqualTo("KB_FORMULA_EVALUATION_FAILED"));
            assertThat(rows(doc)).isEmpty();
        }
        UUID csv=upload(kb,"bad.csv","a,b\n1,2,3\n".getBytes(java.nio.charset.StandardCharsets.UTF_8),"fast",201);
        assertThatThrownBy(()->kernel.ingest(actor(),csv)).isInstanceOfSatisfying(KnowledgeBaseException.class,e->assertThat(e.code()).isEqualTo("KB_CSV_ROW_WIDTH"));
    }
    @Test void imageEmptyWrongModelAndTruncationFailBeforeIndexPublication()throws Exception {
        UUID kb=base();int index=0;
        for(String mode:List.of("EMPTY","CHANGED","TRUNCATED")) {
            model.visionMode=mode;UUID doc=upload(kb,"bad-"+(index++)+".png",files.get("invoice.png"),"fast",201);
            assertThatThrownBy(()->kernel.ingest(actor(),doc)).isInstanceOf(KnowledgeBaseException.class);assertThat(rows(doc)).isEmpty();
        }
    }
    @Test void largeImageArchiveBombAndExtensionMismatchAreBounded()throws Exception {
        UUID kb=base();byte[] image=files.get("invoice.png").clone();var buffer=java.nio.ByteBuffer.wrap(image);buffer.putInt(16,10000);buffer.putInt(20,10000);
        var crc=new java.util.zip.CRC32();crc.update(image,12,17);buffer.putInt(29,(int)crc.getValue());
        UUID doc=upload(kb,"large.png",image,"fast",201);
        assertThatThrownBy(()->kernel.ingest(actor(),doc)).isInstanceOfSatisfying(KnowledgeBaseException.class,e->assertThat(e.code()).isEqualTo("KB_IMAGE_LIMIT"));assertThat(model.visionRequests).isEmpty();
        try(var bytes=new java.io.ByteArrayOutputStream();var zip=new java.util.zip.ZipOutputStream(bytes)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("xl/workbook.xml"));zip.write(new byte[2097152]);zip.closeEntry();zip.finish();upload(kb,"bomb.xlsx",bytes.toByteArray(),"fast",422);
        }
        upload(kb,"fake.docx",files.get("ordinary.pdf"),"fast",415);upload(kb,"unsafe.svg","<svg/>".getBytes(),"fast",415);
    }
    @Test void explicitAliasesUnsupportedProfilesAndMissingConfigurationAreVisible()throws Exception {
        assertThat(parsers.resolve("text/plain","AUTO","a.md","fidelity")).isSameAs(parsers.resolve("text/plain","AUTO","a.md","fast"));
        assertThat(parsers.resolve("application/vnd.ms-excel","AUTO","a.xls","fidelity")).isSameAs(parsers.resolve("application/vnd.ms-excel","AUTO","a.xls","fast"));
        upload(base(),"handbook.docx",files.get("handbook.docx"),"fidelity",415);
        var tree=(com.fasterxml.jackson.databind.node.ObjectNode)json.valueToTree(config);((com.fasterxml.jackson.databind.node.ObjectNode)tree.path("mineru")).put("enabled",false);((com.fasterxml.jackson.databind.node.ObjectNode)tree.path("vlm")).put("enabled",false);
        var off=json.treeToValue(tree,FormatProperties.class);
        assertThatThrownBy(()->new MineruDocumentParser(off,json,local).parse(new ParserInput(Path.of("unused"),"x.pdf","application/pdf",1000,"test",Instant.now().plusSeconds(5))))
                .isInstanceOfSatisfying(KnowledgeBaseException.class,e->assertThat(e.code()).isEqualTo("KB_MINERU_UNAVAILABLE"));
        assertThatThrownBy(()->new AiVlmClient(null,off).describe(new byte[]{1},"image/png","test",Instant.now().plusSeconds(5)))
                .isInstanceOfSatisfying(KnowledgeBaseException.class,e->assertThat(e.code()).isEqualTo("KB_VLM_UNAVAILABLE"));
        var response=mvc.perform(get("/api/knowledge/formats").with(httpBasic("alice","alice-local-test-password"))).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(response.getContentAsString()).contains("LOCAL_TEXT_ONLY","MINERU_CONFIGURED","UNAVAILABLE").doesNotContain(mineru.url());
        mvc.perform(get("/api/knowledge/formats")).andExpect(status().isUnauthorized());
    }
    @Test void hardDeadlineTerminatesSubprocessAndRemovesItsTemporaryArtifacts()throws Exception {
        var node=(com.fasterxml.jackson.databind.node.ObjectNode)json.valueToTree(config);node.put("localTimeout","PT0.001S");
        var shortConfig=json.treeToValue(node,FormatProperties.class);var dir=Path.of("target/knowledge-base/format-spool");Files.createDirectories(dir);var file=dir.resolve("timeout.csv");Files.writeString(file,"a,b\n1,2\n");
        try{assertThatThrownBy(()->new LocalFormatParser(shortConfig,json).parse(new ParserInput(file,"timeout.csv","text/plain",1000,"test",Instant.now().plusSeconds(10))))
                .isInstanceOfSatisfying(KnowledgeBaseException.class,e->assertThat(e.code()).isEqualTo("KB_PARSE_TIMEOUT"));}finally{Files.delete(file);}
    }
    @Test void restartSweepReclaimsPersistedSpoolsButProtectsAnActiveDatabaseLease()throws Exception {
        UUID doc=upload(base(),"quoted.csv",files.get("quoted.csv"),"fast",201);jobs.submit(actor(),doc,"spool-recovery-001",null);var claim=jobs.claimNext();
        var directory=Path.of("target/knowledge-base/format-spool");Files.createDirectories(directory);
        var old=directory.resolve("kb-parser-request-orphan.json");var active=directory.resolve("kb-source-"+claim.jobId()+"-1-orphan.tmp");
        Files.writeString(old,"{}");Files.writeString(active,"source");var time=java.nio.file.attribute.FileTime.from(Instant.now().minusSeconds(1900));
        Files.setLastModifiedTime(old,time);Files.setLastModifiedTime(active,time);
        try {
            spoolCleanup.scan();assertThat(Files.exists(old)).isFalse();assertThat(Files.exists(active)).isTrue();
            jobs.cancel(actor(),claim.jobId());spoolCleanup.scan();assertThat(Files.exists(active)).isFalse();
        }finally{Files.deleteIfExists(old);Files.deleteIfExists(active);}
    }
}
