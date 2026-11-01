package com.lzj.rag.backend.rag.controller;

import com.lzj.rag.backend.knowledgebase.exception.KnowledgeBaseException;
import com.lzj.rag.backend.rag.config.*;
import com.lzj.rag.backend.rag.controller.request.AnswerRequest;
import com.lzj.rag.backend.rag.controller.vo.*;
import com.lzj.rag.backend.rag.service.*;
import com.lzj.rag.backend.rag.service.bo.*;
import com.lzj.rag.backend.security.model.KnowledgeBasePrincipal;
import com.lzj.rag.framework.starter.web.context.RequestIdContext;
import com.lzj.rag.framework.starter.web.result.Results;
import jakarta.annotation.PreDestroy;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@RestController
public class RagStreamController {
    private final RagAnswerService answers;
    private final AnswerProperties config;
    private final ThreadPoolTaskExecutor writers=new ThreadPoolTaskExecutor();

    public RagStreamController(RagAnswerService answers,AnswerProperties config,AnswerExecutionProperties execution) {
        this.answers=answers;this.config=config;
        writers.setCorePoolSize(execution.concurrency());writers.setMaxPoolSize(execution.concurrency());writers.setQueueCapacity(0);
        writers.setThreadNamePrefix("rag-sse-");writers.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());writers.initialize();
    }
    @PostMapping(value="/api/rag/answer/stream",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> stream(@AuthenticationPrincipal KnowledgeBasePrincipal actor,@Valid @RequestBody AnswerRequest request) {
        var channel=new Channel(RequestIdContext.get());
        var task=answers.start(actor,request.toBO(),channel.requestId,true,channel);channel.task=task;
        channel.emitter.onCompletion(channel::disconnect);
        channel.emitter.onError(error->channel.disconnect());
        channel.emitter.onTimeout(()->{answers.timeout(task);channel.disconnect();});
        try {writers.execute(channel::write);}
        catch(RuntimeException full){answers.disconnect(task);channel.emitter.complete();throw new KnowledgeBaseException(503,"RAG_STREAM_CAPACITY","Stream execution capacity is exhausted");}
        return ResponseEntity.ok().header("Cache-Control","no-store").header("X-Accel-Buffering","no").body(channel.emitter);
    }
    @PostMapping("/api/rag/runs/{id}/cancel")
    public ResponseEntity<?> cancel(@AuthenticationPrincipal KnowledgeBasePrincipal actor,@PathVariable UUID id) {
        var run=answers.cancel(actor,id);
        return ResponseEntity.ok().header("Cache-Control","no-store").body(Results.success(Map.of("runId",id,"conversationId",run.conversationId(),"status",run.status())));
    }
    /** 已开始 SSE 的断连不再交给 JSON 异常处理器写第二份响应。 */
    @ExceptionHandler(IOException.class)
    public void streamDisconnected(IOException failure,HttpServletResponse response)throws IOException {
        if(response.isCommitted() && response.getContentType()!=null && response.getContentType().startsWith(MediaType.TEXT_EVENT_STREAM_VALUE))return;
        throw failure;
    }
    private record Event(String name,Object data) {}
    public record Finish(String status,boolean persisted,RagAnswerController.RunVO run) {}

    /** 独立、有界写线程避免慢客户端阻塞模型读取；每流最多缓存 32 个事件。 */
    private final class Channel implements AnswerEvents {
        private final String requestId;
        private final SseEmitter emitter=new SseEmitter(config.timeout().plusSeconds(2).toMillis());
        private final ArrayBlockingQueue<Event> queue=new ArrayBlockingQueue<>(32);
        private final AtomicBoolean closed=new AtomicBoolean();
        private volatile AnswerTask task;
        private volatile Thread writer;
        private boolean recalled;
        Channel(String requestId){this.requestId=requestId;}
        public void meta(AnswerRunBO r){publish("meta",Map.of("requestId",requestId,"runId",r.id(),"answerId",r.id(),"conversationId",r.conversationId(),"deadline",r.deadline()));}
        public void retrieval(RagRetrievalBO r){recalled=true;publish("retrieval",Map.of("phase","COMPLETED","status",r.status(),"degraded",r.query().degraded() || r.retrieval()!=null && r.retrieval().status().equals("DEGRADED"),"strategy",r.retrieval()==null?"NONE":r.retrieval().strategy()));}
        public void sources(EvidenceContextBO context){publish("sources",context==null?Map.of("evidence",List.of()):EvidenceContextVO.from(context));}
        public void message(String text){publish("message",Map.of("text",text));}
        public void error(String code){if(!recalled){publish("retrieval",Map.of("phase","FAILED","status","UNAVAILABLE","degraded",true));recalled=true;}publish("error",Map.of("requestId",requestId,"code",code,"message","本次回答未完整完成，请查看运行状态。"));}
        public void finish(AnswerRunBO run,boolean persisted){publish("finish",new Finish(persisted?run.status():"UNKNOWN",persisted,RagAnswerController.RunVO.from(run)));}
        public void done(){publish("done",Map.of("requestId",requestId));}
        private void publish(String name,Object data) {
            try {
                while(!closed.get()) {
                    if(queue.offer(new Event(name,data),100,TimeUnit.MILLISECONDS))return;
                    var current=task;
                    if(current!=null && Instant.now().isAfter(current.run.deadline().plusSeconds(1)))break;
                }
            } catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
            throw new KnowledgeBaseException(503,"RAG_CLIENT_DISCONNECTED","Stream is closed or cannot accept more events");
        }
        private void write() {
            writer=Thread.currentThread();
            try {
                while(!closed.get()) {
                    Event event=queue.poll(1,TimeUnit.SECONDS);
                    if(event==null) {
                        if(Instant.now().isAfter(task.run.deadline().plusSeconds(2))){answers.timeout(task);break;}
                        emitter.send(SseEmitter.event().comment("heartbeat"));continue;
                    }
                    emitter.send(SseEmitter.event().name(event.name()).data(event.data(),MediaType.APPLICATION_JSON));
                    if(event.name().equals("done"))break;
                }
            } catch(IOException | RuntimeException disconnected){disconnect();}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();disconnect();}
            finally {closed.set(true);queue.clear();emitter.complete();writer=null;Thread.interrupted();}
        }
        private void disconnect() {
            if(closed.compareAndSet(false,true)){queue.clear();answers.disconnect(task);Thread thread=writer;if(thread!=null && thread!=Thread.currentThread())thread.interrupt();}
        }
    }
    @PreDestroy void close(){writers.shutdown();}
}
