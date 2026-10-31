package com.lzj.rag.backend.model.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lzj.rag.backend.model.config.AiClientProperties;
import com.lzj.rag.backend.model.service.bo.RerankInputBO;
import com.lzj.rag.backend.search.config.HybridProperties;
import com.lzj.rag.backend.knowledgebase.exception.KnowledgeBaseException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;

class AiRerankProtocolTest {
    @Test void actualHttpMappingRejectsInvalidIndicesMissingScoresAndRateLimits()throws Exception {
        var json=new ObjectMapper();var body=new AtomicReference<String>();var status=new AtomicInteger(200);
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/ai/rerank",exchange->{
            var request=json.readTree(exchange.getRequestBody());assertThat(request.path("topN").asInt()).isEqualTo(2);
            var bytes=body.get().getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type","application/json");
            exchange.sendResponseHeaders(status.get(),bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        try {
            var transport=new AiEmbeddingClient(new AiClientProperties("http://127.0.0.1:"+server.getAddress().getPort(),null,Duration.ofSeconds(1),Duration.ofSeconds(2),1,10,65536,4194304),json);
            var config=new HybridProperties("AUTO",50,20,60,1,1,2,Duration.ofSeconds(2),Duration.ofSeconds(1),new HybridProperties.Rerank(true,"qwen-rerank",Duration.ofSeconds(1),30000,null));
            var client=new AiRerankClient(transport,config);UUID a=UUID.randomUUID(),b=UUID.randomUUID();
            var input=List.of(new RerankInputBO(a,"first"),new RerankInputBO(b,"second"));
            String good="{\"code\":\"0\",\"data\":{\"model\":\"qwen-rerank\",\"results\":[{\"index\":1,\"score\":0.9,\"chunkId\":\""+b+"\"},{\"index\":0,\"score\":0.2,\"chunkId\":\""+a+"\"}]}}";
            body.set(good);assertThat(client.rerank("query",input,"test",Instant.now().plusSeconds(3)).get(0).index()).isEqualTo(1);
            for(String invalid:List.of(good.replace("\"index\":0","\"index\":1"),good.replace("\"index\":1","\"index\":2"),
                    good.replace("\"score\":0.9,",""),good.replace("\"score\":0.9","\"score\":\"NaN\""),good.replace(b.toString(),a.toString()),
                    "{\"code\":\"0\",\"data\":{\"model\":\"qwen-rerank\",\"results\":[]}}")) {
                body.set(invalid);assertThatThrownBy(()->client.rerank("query",input,"test",Instant.now().plusSeconds(3)))
                        .isInstanceOfSatisfying(KnowledgeBaseException.class,e->assertThat(e.code()).isEqualTo("KB_RERANK_INVALID_RESPONSE"));
            }
            status.set(429);body.set("{\"code\":\"RATE_LIMIT\"}");
            assertThatThrownBy(()->client.rerank("query",input,"test",Instant.now().plusSeconds(3)))
                    .isInstanceOfSatisfying(KnowledgeBaseException.class,e->assertThat(e.code()).isEqualTo("KB_MODEL_RATE_LIMITED"));
        } finally {server.stop(0);}
    }
}
