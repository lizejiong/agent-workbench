package com.lzj.rag.backend.model;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/** Local protocol fixture only; these deterministic vectors are never enabled in production code. */
public final class EmbeddingStub implements AutoCloseable {
    public volatile String mode="GOOD";
    public volatile Runnable beforeEmbed=()->{};
    public volatile java.util.function.Function<String,float[]> vectorizer;
    public volatile String revision="a".repeat(64);
    public final List<JsonNode> requests=new CopyOnWriteArrayList<>();
    public volatile String visionMode="GOOD";
    public final List<JsonNode> visionRequests=new CopyOnWriteArrayList<>();
    public volatile String rerankMode="GOOD";
    public volatile Runnable beforeRerank=()->{};
    public final List<JsonNode> rerankRequests=new CopyOnWriteArrayList<>();
    private final ObjectMapper json=new ObjectMapper();
    private final HttpServer server;
    private final ExecutorService executor=Executors.newFixedThreadPool(4);
    public EmbeddingStub(){
        try{server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);}catch(IOException e){throw new IllegalStateException(e);}
        server.setExecutor(executor);server.createContext("/ai/",this::reply);server.start();
    }
    public String url(){return "http://127.0.0.1:"+server.getAddress().getPort();}
    public void reset(){mode="GOOD";requests.clear();visionMode="GOOD";visionRequests.clear();rerankMode="GOOD";rerankRequests.clear();beforeRerank=()->{};beforeEmbed=()->{};vectorizer=null;revision="a".repeat(64);}
    private void reply(HttpExchange exchange)throws IOException {
        try(exchange){
            Object data;
            if(exchange.getRequestURI().getPath().equals("/ai/rerank")) {
                var request=json.readTree(exchange.getRequestBody());rerankRequests.add(request);beforeRerank.run();
                if(rerankMode.equals("TIMEOUT"))sleep(2000);
                var results=new ArrayList<Map<String,Object>>();int size=request.path("documents").size();
                for(int i=0;i<size;i++) {
                    var row=new LinkedHashMap<String,Object>();row.put("index",rerankMode.equals("DUPLICATE")?0:rerankMode.equals("OUT_OF_RANGE")?size:i);
                    row.put("chunkId",rerankMode.equals("WRONG_ID")?"invented":request.path("documents").get(i).path("id").asText());
                    if(!rerankMode.equals("MISSING_SCORE"))row.put("score",rerankMode.equals("NON_NUMERIC")?"NaN":(i+1.0)/(size+1));results.add(row);
                }
                if(rerankMode.equals("SHORT") && !results.isEmpty())results.remove(0);
                data=Map.of("results",results,"model",rerankMode.equals("WRONG_MODEL")?"other-rerank":"qwen-rerank");
            }else if(exchange.getRequestURI().getPath().equals("/ai/vision")) {
                visionRequests.add(json.readTree(exchange.getRequestBody()));
                data=Map.of("content",visionMode.equals("EMPTY")?" ":"Description: invoice. Visible text: INVOICE 42; Apples: 2.","model",visionMode.equals("CHANGED")?"another-model":"qwen-vision","finishReason",visionMode.equals("TRUNCATED")?"length":"stop");
            }else if(exchange.getRequestMethod().equals("GET")) {
                data=Map.ofEntries(Map.entry("modelId","qwen-embed"),Map.entry("provider","bailian"),Map.entry("modelName","text-embedding-v4"),
                        Map.entry("dimensions",1024),Map.entry("embeddingSpace","bailian-text-embedding-v4"),
                        Map.entry("configVersion",mode.equals("METADATA_CHANGED")?"changed":"bailian-text-embedding-v4-1024-v1"),
                        Map.entry("configRevision",mode.equals("REVISION_CHANGED")?"b".repeat(64):revision),Map.entry("batchSize",2),Map.entry("active",!mode.equals("UNAVAILABLE")),Map.entry("protocolVersion","embed-v1"));
            }else{
                var request=json.readTree(exchange.getRequestBody());requests.add(request);beforeEmbed.run();
                if(Set.of("RATE_LIMIT","UPSTREAM_RATE_LIMIT","AUTH","UPSTREAM_INVALID").contains(mode)) {
                    String code=switch(mode){case "AUTH"->"AUTH";case "UPSTREAM_INVALID"->"INVALID_RESPONSE";default->"RATE_LIMIT";};
                    byte[] failure=json.writeValueAsBytes(Map.of("code",code,"message","private upstream message must not be persisted"));
                    exchange.getResponseHeaders().set("Content-Type","application/json");
                    exchange.sendResponseHeaders(mode.equals("RATE_LIMIT")?429:mode.equals("UPSTREAM_RATE_LIMIT")?503:502,failure.length);
                    exchange.getResponseBody().write(failure);return;
                }
                if(mode.equals("TIMEOUT"))sleep(2000);
                var vectors=new ArrayList<Map<String,Object>>();int size=request.path("texts").size();
                for(int i=size-1;i>=0;i--){
                    int dim=mode.equals("WRONG_DIM")?1023:1024;
                    var vector=new ArrayList<Object>(Collections.nCopies(dim,0.0));
                    if(!mode.equals("ZERO"))vector.set(0,(double)request.path("texts").get(i).asText().length());
                    if(mode.equals("NAN"))vector.set(0,"NaN");
                    if(mode.equals("GOOD") && vectorizer!=null) {
                        vector.clear();for(float value:vectorizer.apply(request.path("texts").get(i).asText()))vector.add(value);
                    }
                    vectors.add(Map.of("index",mode.equals("DUPLICATE")?0:i,"values",vector));
                }
                if(mode.equals("EMPTY"))vectors.clear();
                if(mode.equals("MISSING") && !vectors.isEmpty())vectors.remove(0);
                data=Map.of("embeddings",vectors,"dimensions",1024,"model","qwen-embed","provider","bailian",
                        "embeddingSpace","bailian-text-embedding-v4","configRevision",mode.equals("STALE")?"b".repeat(64):revision);
            }
            byte[] body=json.writeValueAsBytes(Map.of("code","0","data",data));
            if(mode.equals("OVERSIZED"))body=(" ".repeat(70000)+new String(body,java.nio.charset.StandardCharsets.UTF_8)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);
            if(mode.equals("SLOW_BODY")){exchange.getResponseBody().write(body,0,1);exchange.getResponseBody().flush();sleep(2000);exchange.getResponseBody().write(body,1,body.length-1);}
            else exchange.getResponseBody().write(body);
        }catch(IOException ignored){ /* Timed-out clients intentionally close responses. */ }
    }
    private static void sleep(long millis){try{Thread.sleep(millis);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
    public void close(){server.stop(0);executor.shutdownNow();}
}
