package com.lzj.rag.backend.model.client;

import com.fasterxml.jackson.databind.*;
import com.lzj.rag.backend.knowledgebase.exception.KnowledgeBaseException;
import com.lzj.rag.backend.model.config.AiClientProperties;
import com.lzj.rag.backend.model.service.StreamChatClient;
import okhttp3.*;
import org.springframework.stereotype.Component;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.PreDestroy;

/** 适配仓库现有 /ai/chat/stream 协议；不在可见输出后重试。 */
@Component
public class AiStreamChatClient implements StreamChatClient {
    private final AiClientProperties config;
    private final AiEmbeddingClient budget;
    private final ObjectMapper json;
    private final OkHttpClient client;

    public AiStreamChatClient(AiClientProperties config,AiEmbeddingClient budget,ObjectMapper json) {
        this.config=config;this.budget=budget;this.json=json;
        client=new OkHttpClient.Builder().connectTimeout(config.connectTimeout()).readTimeout(config.callTimeout())
                .writeTimeout(config.callTimeout()).retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build();
    }

    public JsonNode stream(List<Map<String,String>> messages,String model,int maxOutputTokens,String requestId,
                           Instant deadline,Cancellation cancellation,java.util.function.Consumer<String> text) {
        cancellation.check();
        try(var permit=budget.reserve(deadline)) {
            cancellation.check();
            byte[] encoded=json.writeValueAsBytes(Map.of("messages",messages,"preferredModel",model,"json",false,"thinking",false,"maxOutputTokens",maxOutputTokens));
            var request=new Request.Builder().url(config.baseUrl().replaceAll("/+$","")+"/ai/chat/stream")
                    .header("X-Request-Id",requestId).header("Accept","text/event-stream")
                    .post(RequestBody.create(encoded,MediaType.get("application/json")));
            if(config.token()!=null && !config.token().isBlank())request.header("Authorization","Bearer "+config.token());
            Call call=client.newCall(request.build());
            // 整流服从问答剩余总预算，读写空闲仍使用现有 HTTP call-timeout。
            call.timeout().timeout(Math.max(1,Duration.between(Instant.now(),deadline).toMillis()),TimeUnit.MILLISECONDS);
            Runnable cancel=call::cancel;
            cancellation.register(cancel);
            try(var response=call.execute()) {
                int status=response.code();
                if(status==408 || status==504)throw failure(504,"KB_MODEL_TIMEOUT");
                if(status==429)throw failure(503,"KB_MODEL_RATE_LIMITED");
                if(status>=400 && status<500)throw failure(502,"KB_MODEL_REQUEST_REJECTED");
                if(!response.isSuccessful())throw failure(503,"KB_MODEL_UNAVAILABLE");
                if(response.body()==null || !response.header("Content-Type","").toLowerCase(Locale.ROOT).startsWith("text/event-stream"))throw invalid();
                var source=response.body().source();
                String event="";StringBuilder data=new StringBuilder();long bytes=0;boolean started=false;String actualModel=null;
                JsonNode usage=null;
                while(!source.exhausted()) {
                    cancellation.check();
                    // readUtf8LineStrict 限制单行分配；总流也受同一字节上限约束。
                    String line=source.readUtf8LineStrict(Math.min(65536,config.maxResponseBytes()));
                    bytes+=line.getBytes(StandardCharsets.UTF_8).length+1;
                    if(bytes>config.maxResponseBytes())throw invalid();
                    if(line.isEmpty()) {
                        if(data.isEmpty()){event="";continue;}
                        JsonNode frame=json.readTree(data.toString());
                        if(frame==null || !frame.isObject() || !requestId.equals(frame.path("requestId").asText())
                                || !frame.path("payload").isObject())throw invalid();
                        String frameModel=frame.path("model").asText();
                        if(frameModel.isBlank() || actualModel!=null && !actualModel.equals(frameModel))throw invalid();
                        actualModel=frameModel;
                        JsonNode payload=frame.path("payload");
                        switch(event) {
                            case "start" -> {if(started)throw invalid();started=true;cancellation.startedModel(actualModel);}
                            case "content" -> {
                                if(!started || !payload.path("text").isTextual())throw invalid();
                                if(!payload.path("text").asText().isEmpty())text.accept(payload.path("text").asText());
                            }
                            case "reasoning" -> {if(!started)throw invalid();} // 不向用户或历史转发隐藏推理。
                            case "usage" -> {if(!started || usage!=null)throw invalid();usage=payload;}
                            case "done" -> {
                                if(!started || !"stop".equals(payload.path("finishReason").asText()))throw invalid();
                                var result=json.createObjectNode().put("model",actualModel).put("finishReason","stop");
                                if(usage!=null)result.set("usage",usage);
                                cancellation.check();return result;
                            }
                            case "error" -> {
                                String code=payload.path("code").asText();
                                throw failure(code.equals("TIMEOUT")?504:503,code.equals("TIMEOUT")?"KB_MODEL_TIMEOUT":code.equals("RATE_LIMIT")?"KB_MODEL_RATE_LIMITED":"KB_MODEL_UNAVAILABLE");
                            }
                            default -> throw invalid(); // 本问答不接受工具调用或未知输出。
                        }
                        event="";data.setLength(0);
                    } else if(line.startsWith("event:"))event=line.substring(6).stripLeading();
                    else if(line.startsWith("data:")){if(!data.isEmpty())data.append('\n');data.append(line.substring(5).stripLeading());}
                    else if(!line.startsWith(":"))throw invalid();
                }
                throw invalid(); // EOF 不等于上游成功结束。
            } finally {cancellation.unregister(cancel);call.cancel();}
        } catch(KnowledgeBaseException e){throw e;}
        catch(com.fasterxml.jackson.core.JsonProcessingException e){throw invalid();}
        catch(java.io.InterruptedIOException e){cancellation.check();throw failure(504,"KB_MODEL_TIMEOUT");}
        catch(IOException e){cancellation.check();throw failure(502,"RAG_MODEL_STREAM_INCOMPLETE");}
        catch(Exception e){cancellation.check();throw failure(503,"KB_MODEL_UNAVAILABLE");}
    }
    private KnowledgeBaseException invalid(){return failure(502,"RAG_MODEL_INVALID_RESPONSE");}
    private KnowledgeBaseException failure(int status,String code){return new KnowledgeBaseException(status,code,"Model stream did not complete");}
    @PreDestroy void close(){client.dispatcher().executorService().shutdownNow();client.connectionPool().evictAll();}
}
