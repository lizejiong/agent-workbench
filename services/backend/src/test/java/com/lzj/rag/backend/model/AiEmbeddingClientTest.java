package com.lzj.rag.backend.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lzj.rag.backend.knowledgebase.KnowledgeBaseException;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class AiEmbeddingClientTest {
    private EmbeddingStub server;
    private final EmbeddingClient.Binding binding=new EmbeddingClient.Binding("qwen-embed","bailian","text-embedding-v4",1024,"bailian-text-embedding-v4","bailian-text-embedding-v4-1024-v1","c".repeat(64));
    @BeforeEach void start(){server=new EmbeddingStub();}
    @AfterEach void stop(){server.close();}
    private AiEmbeddingClient client(Duration timeout){return new AiEmbeddingClient(new AiClientProperties(server.url(),null,Duration.ofSeconds(1),timeout,2,10,65536,65536),new ObjectMapper());}
    @Test void batchesToServerLimitAndReordersReturnedIndices(){
        var result=client(Duration.ofSeconds(2)).embed(binding,List.of("a","bb","ccc","dddd","eeeee"),"test-order",Instant.now().plusSeconds(10));
        assertThat(result.vectors().stream().map(v->v[0]).toList()).containsExactly(1f,2f,3f,4f,5f);
        assertThat(server.requests).hasSize(3).allSatisfy(r->{assertThat(r.path("texts").size()).isLessThanOrEqualTo(2);assertThat(r.path("model").asText()).isEqualTo("qwen-embed");assertThat(r.path("expectedConfigRevision").asText()).isEqualTo("a".repeat(64));});
    }
    @Test void rejectsEmptyMissingWrongDimensionDuplicateZeroAndIllegalValues(){
        var client=client(Duration.ofSeconds(2));
        for(String mode:List.of("EMPTY","MISSING","WRONG_DIM","DUPLICATE","ZERO","NAN","OVERSIZED")){
            server.mode=mode;
            assertThatThrownBy(()->client.embed(binding,List.of("a","bb"),"test-invalid",Instant.now().plusSeconds(4)))
                    .as(mode).isInstanceOfSatisfying(KnowledgeBaseException.class,e->assertThat(e.code()).isEqualTo("KB_MODEL_INVALID_RESPONSE"));
        }
    }
    @Test void rejectsUnavailableAndChangedSpaceWithoutFallback(){
        var client=client(Duration.ofSeconds(2));
        for(String mode:List.of("UNAVAILABLE","METADATA_CHANGED","STALE")){
            server.mode=mode;
            assertThatThrownBy(()->client.embed(binding,List.of("a"),"test-identity",Instant.now().plusSeconds(4)))
                    .isInstanceOfSatisfying(KnowledgeBaseException.class,e->assertThat(e.code()).isEqualTo(mode.equals("UNAVAILABLE")?"KB_MODEL_UNAVAILABLE":"KB_MODEL_CONFIG_CHANGED"));
        }
        assertThat(server.requests).hasSize(1); // only STALE reached POST; no substitute model was requested.
    }
    @Test void boundsBothTimeToHeadersAndSlowResponseBody(){
        var client=client(Duration.ofMillis(300));
        for(String mode:List.of("TIMEOUT","SLOW_BODY")){
            server.mode=mode;long start=System.nanoTime();
            assertThatThrownBy(()->client.embed(binding,List.of("a"),"test-timeout",Instant.now().plusSeconds(2)))
                    .isInstanceOfSatisfying(KnowledgeBaseException.class,e->assertThat(e.code()).isEqualTo("KB_MODEL_TIMEOUT"));
            assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofSeconds(2));
        }
    }
    @Test void distinguishesRateLimitingFromPermanentProviderErrors(){
        var client=client(Duration.ofSeconds(2));
        for(String mode:List.of("RATE_LIMIT","UPSTREAM_RATE_LIMIT","AUTH","UPSTREAM_INVALID")) {
            server.mode=mode;
            String expected=mode.contains("RATE_LIMIT")?"KB_MODEL_RATE_LIMITED":mode.equals("AUTH")?"KB_MODEL_REQUEST_REJECTED":"KB_MODEL_INVALID_RESPONSE";
            assertThatThrownBy(()->client.embed(binding,List.of("a"),"test-errors",Instant.now().plusSeconds(4)))
                    .isInstanceOfSatisfying(KnowledgeBaseException.class,e->assertThat(e.code()).isEqualTo(expected));
        }
    }
}
