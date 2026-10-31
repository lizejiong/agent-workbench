package com.lzj.rag.backend.search.channel;


import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.lzj.rag.backend.document.service.internal.DocumentAccess;
import com.lzj.rag.backend.knowledgebase.dao.entity.KnowledgeBaseDO;
import com.lzj.rag.backend.knowledgebase.exception.KnowledgeBaseException;
import com.lzj.rag.backend.knowledgebase.service.internal.KnowledgeBaseAccess;
import com.lzj.rag.backend.model.service.EmbeddingClient;
import com.lzj.rag.backend.model.service.bo.EmbeddingResultBO;
import com.lzj.rag.backend.search.channel.VectorSearchChannel;
import com.lzj.rag.backend.search.config.SearchProperties;
import com.lzj.rag.backend.search.repository.vector.VectorStore;
import com.lzj.rag.backend.search.service.bo.SearchBO;
import com.lzj.rag.backend.search.service.impl.KnowledgeSearchServiceImpl;
import com.lzj.rag.backend.security.model.KnowledgeBasePrincipal;

class VectorSearchDeadlineTest {
    private final KnowledgeBaseAccess bases=mock(KnowledgeBaseAccess.class);
    private final EmbeddingClient embeddings=mock(EmbeddingClient.class);
    private final VectorStore vectors=mock(VectorStore.class);
    private final KnowledgeBasePrincipal actor=new KnowledgeBasePrincipal("alice","unused","tenant-a","alice");
    private final UUID kb=UUID.randomUUID();
    private final SearchBO request=new SearchBO("database",List.of(kb),null,5,null);
    private VectorSearchChannel channel() {
        return new VectorSearchChannel(new KnowledgeSearchServiceImpl(bases,mock(DocumentAccess.class),embeddings,vectors,new ObjectMapper(),new SearchProperties(50,16,100,4000,Duration.ofSeconds(40))));
    }
    @Test void channelDeadlineReachesTheActualEmbeddingClientWithoutRestartingTheBudget() {
        var base=new KnowledgeBaseDO();base.setId(kb);base.setEmbeddingModelId("qwen-embed");base.setEmbeddingProvider("bailian");base.setEmbeddingDimension(1024);
        base.setEmbeddingConfigRevision("c".repeat(64));base.setEmbeddingAiConfigRevision("a".repeat(64));base.setVectorNamespace("test-namespace");
        base.setEmbeddingConfigJson("{\"modelName\":\"text-embedding-v4\",\"embeddingSpace\":\"bailian-text-embedding-v4\",\"configVersion\":\"v1\"}");
        when(bases.requireOwned(actor,kb)).thenReturn(base);
        float[] vector=new float[1024];vector[0]=1;
        when(embeddings.embed(any(),anyList(),anyString(),any())).thenReturn(new EmbeddingResultBO(List.of(vector),"a".repeat(64)));
        when(vectors.query(anyList(),any(),anyString(),anyList(),anyInt(),any())).thenReturn(List.of());
        Instant deadline=Instant.now().plusSeconds(2);
        assertThat(channel().search(actor,request,"short-deadline",5,deadline).candidates()).isEmpty();
        verify(embeddings).embed(argThat(binding->binding.expectedAiRevision().equals("a".repeat(64))),eq(List.of("database")),eq("short-deadline"),eq(deadline));
    }
    @Test void expiredChannelDoesNotMakeAPaidEmbeddingCallOrReadTheIndex() {
        assertThatThrownBy(()->channel().search(actor,request,"expired",5,Instant.now().minusSeconds(1)))
                .isInstanceOfSatisfying(KnowledgeBaseException.class,e->assertThat(e.code()).isEqualTo("KB_SEARCH_TIMEOUT"));
        verifyNoInteractions(embeddings,vectors,bases);
    }
}
