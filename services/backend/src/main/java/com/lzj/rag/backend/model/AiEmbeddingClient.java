package com.lzj.rag.backend.model;

import com.fasterxml.jackson.databind.*;
import com.lzj.rag.backend.knowledgebase.KnowledgeBaseException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

@Component
@EnableConfigurationProperties(AiClientProperties.class)
public class AiEmbeddingClient implements EmbeddingClient {
    private final AiClientProperties config;
    private final ObjectMapper json;
    private final Semaphore permits;
    private final HttpClient client;
    public AiEmbeddingClient(AiClientProperties config,ObjectMapper json) {
        this.config=config;this.json=json;permits=new Semaphore(config.concurrency());
        URI uri=URI.create(config.baseUrl());
        if(!Set.of("http","https").contains(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null)
            throw new IllegalArgumentException("Invalid AI service URL");
        if(config.connectTimeout().isZero() || config.connectTimeout().isNegative() || config.callTimeout().isZero() || config.callTimeout().isNegative())
            throw new IllegalArgumentException("AI timeouts must be positive");
        client=HttpClient.newBuilder().connectTimeout(config.connectTimeout()).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    @Override public Result embed(Binding binding,List<String> texts,String requestId,Instant deadline) {
        if(texts.isEmpty())throw invalid();
        JsonNode descriptor=call("GET","/ai/embedding-models/"+binding.modelId()+"?dimensions="+binding.dimension(),null,requestId,deadline);
        if(!descriptor.path("active").isBoolean() || !descriptor.path("active").booleanValue())throw failure(503,"KB_MODEL_UNAVAILABLE","Bound embedding model is unavailable");
        identity(descriptor,binding);
        if(!"embed-v1".equals(descriptor.path("protocolVersion").asText()) || !descriptor.path("batchSize").isIntegralNumber() || descriptor.path("batchSize").asInt()<1)throw invalid();
        String revision=descriptor.path("configRevision").asText();
        if(!revision.matches("[0-9a-f]{64}"))throw invalid();
        if(binding.expectedAiRevision()!=null && !binding.expectedAiRevision().equals(revision))throw changed();
        int batchLimit=Math.min(config.batchSize(),descriptor.path("batchSize").asInt());
        var vectors=new ArrayList<float[]>();int start=0;
        while(start<texts.size()) {
            int end=start;
            // Budget uses encoded request bytes, including JSON escaping and identity fields.
            Map<String,Object> body;
            do {
                end++;
                body=Map.of("texts",texts.subList(start,end),"model",binding.modelId(),"dimensions",binding.dimension(),"expectedConfigRevision",revision);
                if(bytes(body).length>config.maxBatchBytes()) {
                    end--;if(end==start)throw failure(422,"KB_EMBEDDING_INPUT_TOO_LARGE","Single embedding input exceeds configured request byte budget");break;
                }
            } while(end<texts.size() && end-start<batchLimit);
            body=Map.of("texts",texts.subList(start,end),"model",binding.modelId(),"dimensions",binding.dimension(),"expectedConfigRevision",revision);
            var response=call("POST","/ai/embed",body,requestId,deadline);
            if(!binding.modelId().equals(response.path("model").asText()) || !binding.provider().equals(response.path("provider").asText())
                    || !binding.embeddingSpace().equals(response.path("embeddingSpace").asText()) || !revision.equals(response.path("configRevision").asText())
                    || !response.path("dimensions").isIntegralNumber() || response.path("dimensions").asInt()!=binding.dimension())throw changed();
            var values=response.path("embeddings");int count=end-start;
            if(!values.isArray() || values.size()!=count)throw invalid();
            float[][] ordered=new float[count][];
            for(var entry:values) {
                var index=entry.path("index");if(!index.isIntegralNumber() || !index.canConvertToInt())throw invalid();
                int n=index.intValue();if(n<0 || n>=count || ordered[n]!=null || !entry.path("values").isArray() || entry.path("values").size()!=binding.dimension())throw invalid();
                float[] vector=new float[binding.dimension()];boolean nonzero=false;
                for(int i=0;i<vector.length;i++) {
                    var value=entry.path("values").get(i);
                    if(!value.isNumber() || !Double.isFinite(value.doubleValue()))throw invalid();
                    vector[i]=(float)value.doubleValue();if(!Float.isFinite(vector[i]))throw invalid();nonzero|=vector[i]!=0;
                }
                if(!nonzero)throw invalid();ordered[n]=vector;
            }
            vectors.addAll(Arrays.asList(ordered));start=end;
        }
        return new Result(List.copyOf(vectors),revision);
    }
    private void identity(JsonNode descriptor,Binding binding) {
        if(!binding.modelId().equals(descriptor.path("modelId").asText()) || !binding.provider().equals(descriptor.path("provider").asText())
                || !binding.modelName().equals(descriptor.path("modelName").asText()) || !binding.embeddingSpace().equals(descriptor.path("embeddingSpace").asText())
                || !binding.configVersion().equals(descriptor.path("configVersion").asText()) || !descriptor.path("dimensions").isIntegralNumber()
                || descriptor.path("dimensions").asInt()!=binding.dimension())throw changed();
    }
    public JsonNode visionCall(Map<String,Object> body,String requestId,Instant deadline){return call("POST","/ai/vision",body,requestId,deadline);}
    public JsonNode rerankCall(Map<String,Object> body,String requestId,Instant deadline){return call("POST","/ai/rerank",body,requestId,deadline);}
    private JsonNode call(String method,String path,Object body,String requestId,Instant deadline) {
        long remaining=Duration.between(Instant.now(),deadline).toMillis();if(remaining<=0)throw timeout();
        boolean acquired=false;
        try {
            acquired=permits.tryAcquire(remaining,TimeUnit.MILLISECONDS);if(!acquired)throw timeout();
            remaining=Duration.between(Instant.now(),deadline).toMillis();if(remaining<=0)throw timeout();
            long timeout=Math.min(remaining,config.callTimeout().toMillis());
            var request=HttpRequest.newBuilder(URI.create(config.baseUrl().replaceAll("/+$","")+path))
                    .timeout(Duration.ofMillis(timeout)).header("X-Request-Id",requestId).header("Content-Type","application/json")
                    .method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(bytes(body)));
            if(config.token()!=null && !config.token().isBlank())request.header("Authorization","Bearer "+config.token());
            var future=client.sendAsync(request.build(),info->new LimitedBody(config.maxResponseBytes()));
            HttpResponse<byte[]> response;
            try { response=future.get(timeout,TimeUnit.MILLISECONDS); }
            catch(TimeoutException e) { future.cancel(true);throw timeout(); }
            catch(InterruptedException e) { future.cancel(true);throw e; }
                int status=response.statusCode();
                String errorCode="";
                if(status<200 || status>=300) {
                    try {var error=json.readTree(response.body());if(error!=null)errorCode=error.path("code").asText();}
                    catch(com.fasterxml.jackson.core.JsonProcessingException ignored){ }
                }
                if(status==409)throw changed();
                if(status==408 || status==504)throw timeout();
                if(status==429 || errorCode.equals("RATE_LIMIT"))throw failure(503,"KB_MODEL_RATE_LIMITED","Bound model rate limit reached; retry with backoff");
                if(errorCode.equals("INVALID_RESPONSE"))throw invalid();
                if(errorCode.equals("AUTH"))throw failure(502,"KB_MODEL_REQUEST_REJECTED","Check the bound model credentials in the AI service");
                if(status==400 || status==401 || status==403 || status==404 || status==415 || status==422)
                    throw failure(502,"KB_MODEL_REQUEST_REJECTED","Bound model request was rejected; check model configuration");
                if(status<200 || status>=300)throw failure(503,"KB_MODEL_UNAVAILABLE","Bound model request failed; no fallback was attempted");
                byte[] data=response.body();
                if(data.length>config.maxResponseBytes())throw invalid();
                JsonNode root=json.readTree(data);
                if(root==null || !"0".equals(root.path("code").asText()) || !root.path("data").isObject())throw invalid();
                return root.path("data");
        } catch(KnowledgeBaseException e) { throw e; }
        catch(InterruptedException e) { Thread.currentThread().interrupt();throw timeout(); }
        catch(ExecutionException e) {
            if(e.getCause() instanceof java.net.http.HttpTimeoutException)throw timeout();
            if(e.getCause() instanceof IllegalStateException)throw invalid();
            throw failure(503,"KB_MODEL_UNAVAILABLE","Embedding service connection failed");
        }
        catch(com.fasterxml.jackson.core.JsonProcessingException e) { throw invalid(); }
        catch(java.io.IOException e) { throw invalid(); }
        finally { if(acquired)permits.release(); }
    }
    public static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int max;
        private final java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();
        private java.util.concurrent.Flow.Subscription subscription;
        public LimitedBody(int max){this.max=max;}
        public CompletionStage<byte[]> getBody(){return result;}
        public void onSubscribe(java.util.concurrent.Flow.Subscription subscription){this.subscription=subscription;subscription.request(1);}
        public void onNext(List<ByteBuffer> items){
            for(var item:items){
                if((long)out.size()+item.remaining()>max){subscription.cancel();result.completeExceptionally(new IllegalStateException("AI response exceeded limit"));return;}
                byte[] bytes=new byte[item.remaining()];item.get(bytes);out.writeBytes(bytes);
            }
            subscription.request(1);
        }
        public void onError(Throwable error){result.completeExceptionally(error);}
        public void onComplete(){result.complete(out.toByteArray());}
    }
    private byte[] bytes(Object value) {
        try {return json.writeValueAsBytes(value);}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw invalid();}
    }
    private static KnowledgeBaseException changed(){return failure(409,"KB_MODEL_CONFIG_CHANGED","Bound embedding identity or configuration changed; rebuild using a new knowledge base");}
    private static KnowledgeBaseException invalid(){return failure(502,"KB_MODEL_INVALID_RESPONSE","Embedding service returned an invalid response");}
    private static KnowledgeBaseException timeout(){return failure(504,"KB_MODEL_TIMEOUT","Embedding call or ingestion deadline exceeded");}
    private static KnowledgeBaseException failure(int status,String code,String message){return new KnowledgeBaseException(status,code,message);}
}
