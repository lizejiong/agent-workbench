package com.lzj.rag.backend.rag.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lzj.rag.backend.rag.config.ContextProperties;
import com.lzj.rag.backend.rag.service.bo.QueryPlanBO;
import com.lzj.rag.backend.model.service.TokenCounterClient;
import com.lzj.rag.backend.search.repository.EvidenceRepository;
import com.lzj.rag.backend.search.service.bo.*;
import com.lzj.rag.backend.security.model.KnowledgeBasePrincipal;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class EvidenceAssemblerTest {
    private final EvidenceRepository records=mock(EvidenceRepository.class);
    private final TokenCounterClient tokens=mock(TokenCounterClient.class);
    private final ObjectMapper json=new ObjectMapper();
    private final UUID kb=UUID.randomUUID();
    private final KnowledgeBasePrincipal actor=new KnowledgeBasePrincipal("alice","unused","tenant","alice");
    private final SearchBO scope=new SearchBO("database backup",List.of(kb),null,5,null);
    private QueryPlanBO plan(){return new QueryPlanBO("database backup","database backup",List.of("database","backup"),List.of(kb),false,null,false,null,"v1",null,"terms","hash");}
    private EvidenceBO evidence(UUID document,String content,String sub,Map<String,Object> metadata) {
        return new EvidenceBO(UUID.randomUUID(),kb,document,"guide",0,1,content,.9,"RRF",metadata,"/api/documents/"+document+"/file",.03,null,List.of(new ChannelScoreBO("VECTOR",1,.9,"COSINE_SIMILARITY",sub)));
    }
    private EvidenceAssembler assembler(ContextProperties config) {
        when(tokens.estimate(anyString(),anyString(),any())).thenAnswer(c->new TokenCounterClient.Estimate(c.<String>getArgument(0).codePointCount(0,c.<String>getArgument(0).length())/3,true));
        return new EvidenceAssembler(records,tokens,config,json);
    }
    @Test void queryCoveragePrecedesExtraChunksFromTheSameDocumentAndModelDataOmitsInternalIdentifiers() {
        UUID doc=UUID.randomUUID();var a=evidence(doc,"database connection","database",Map.of("lineStart",1));
        var b=evidence(doc,"database details","database",Map.of("lineStart",2));var c=evidence(UUID.randomUUID(),"backup steps","backup",Map.of());
        when(records.hydrate(any(),any(),any())).thenReturn(Map.of(a.chunkId(),a,b.chunkId(),b,c.chunkId(),c));
        var result=assembler(new ContextProperties(8192,1024,1024,512,2,1,2,1,0))
                .assemble(actor,scope,plan(),List.of(a,b,c),List.of(),"test",Instant.now().plusSeconds(5));
        assertThat(result.evidence().stream().map(e->e.chunkId()).toList()).containsExactly(a.chunkId(),c.chunkId());
        assertThat(result.evidence().stream().map(e->e.evidenceId()).toList()).containsExactly("E1","E2");
        assertThat(result.modelContext()).doesNotContain(kb.toString(),doc.toString(),"/api/documents/");
        assertThat(result.evidence().get(1).sourceMetadata()).doesNotContainKey("pageNumber");
    }
    @Test void tableTrimmingKeepsItsHeaderAndWholeRowsAndCountsSourceMetadataInTheBudget() {
        String table="| 名称 | 数量 |\n| --- | --- |\n"+"| 苹果 | 100 公斤 |\n".repeat(100);
        var row=evidence(UUID.randomUUID(),table,"database",Map.of("chapterPath",List.of("章节".repeat(100))));
        when(records.hydrate(any(),any(),any())).thenReturn(Map.of(row.chunkId(),row));
        var result=assembler(new ContextProperties(2048,128,128,128,5,2,3,2,0))
                .assemble(actor,scope,plan(),List.of(row),List.of(),"test",Instant.now().plusSeconds(5));
        var snapshot=result.evidence().get(0);assertThat(snapshot.truncated()).isTrue();
        assertThat(snapshot.content()).startsWith("| 名称 | 数量 |\n| --- | --- |\n");
        for(String line:snapshot.content().split("\n"))assertThat(line).endsWith("|");
        assertThat(snapshot.snapshotHash()).isNotEqualTo(snapshot.sourceContentHash());
        assertThat(result.modelContext().getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThan(result.inputTokenBudget());
    }
    @Test void revokedSnapshotNeverReachesTheExternalTokenCounter() {
        var row=evidence(UUID.randomUUID(),"database","database",Map.of());
        when(records.hydrate(any(),any(),any())).thenReturn(Map.of(row.chunkId(),row),Map.of());
        var assembler=new EvidenceAssembler(records,tokens,new ContextProperties(8192,1024,1024,512,5,2,3,2,0),json);
        assertThatThrownBy(()->assembler.assemble(actor,scope,plan(),List.of(row),List.of(),"test",Instant.now().plusSeconds(5)))
                .isInstanceOfSatisfying(com.lzj.rag.backend.knowledgebase.exception.KnowledgeBaseException.class,e->assertThat(e.code()).isEqualTo("RAG_EVIDENCE_CHANGED"));
        verifyNoInteractions(tokens);
    }
}
