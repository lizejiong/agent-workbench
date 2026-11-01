package com.lzj.rag.backend.rag.service.impl;

import com.lzj.rag.backend.knowledgebase.exception.KnowledgeBaseException;
import com.lzj.rag.backend.rag.config.*;
import com.lzj.rag.backend.rag.repository.AnswerLedger;
import com.lzj.rag.backend.rag.service.*;
import com.lzj.rag.backend.rag.service.bo.*;
import com.lzj.rag.backend.security.model.KnowledgeBasePrincipal;
import jakarta.annotation.PreDestroy;
import org.slf4j.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

@Service
@EnableConfigurationProperties(AnswerExecutionProperties.class)
public class RagAnswerServiceImpl implements RagAnswerService {
    private static final Logger log=LoggerFactory.getLogger(RagAnswerServiceImpl.class);
    private final AnswerPipeline pipeline;
    private final AnswerLedger ledger;
    private final AnswerProperties config;
    private final ThreadPoolTaskExecutor executor=new ThreadPoolTaskExecutor();
    private final Semaphore capacity;
    private final ConcurrentMap<UUID,AnswerTask> active=new ConcurrentHashMap<>();
    private final ScheduledExecutorService monitor=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"rag-answer-monitor");t.setDaemon(true);return t;});

    public RagAnswerServiceImpl(AnswerPipeline pipeline,AnswerLedger ledger,AnswerProperties config,AnswerExecutionProperties execution) {
        this.pipeline=pipeline;this.ledger=ledger;this.config=config;capacity=new Semaphore(execution.concurrency());
        executor.setCorePoolSize(execution.concurrency());executor.setMaxPoolSize(execution.concurrency());executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("rag-answer-");executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());executor.initialize();
        monitor.scheduleWithFixedDelay(this::tick,1,1,TimeUnit.SECONDS);
    }
    public RagAnswerBO answer(KnowledgeBasePrincipal actor,RagRetrieveBO request,String requestId) {
        var task=start(actor,request,requestId,false,AnswerEvents.NONE);
        try {return task.completion.get(task.remainingNanos(),TimeUnit.NANOSECONDS);}
        catch(TimeoutException timeout){stop(task,"TIMED_OUT","RAG_ANSWER_TIMEOUT");throw new AnswerFailure(504,"RAG_ANSWER_TIMEOUT",task.run);}
        catch(InterruptedException interrupted){stop(task,"CANCELLED","RAG_ANSWER_CANCELLED");Thread.currentThread().interrupt();throw new AnswerFailure(503,"RAG_ANSWER_CANCELLED",task.run);}
        catch(ExecutionException failure){if(failure.getCause() instanceof RuntimeException error)throw error;throw new AnswerFailure(500,"RAG_INTERNAL",task.run);}
    }
    public AnswerTask start(KnowledgeBasePrincipal actor,RagRetrieveBO request,String requestId,boolean streaming,AnswerEvents events) {
        if(!config.enabled())throw new KnowledgeBaseException(503,"RAG_ANSWER_DISABLED","Standalone answering is disabled");
        if(!capacity.tryAcquire())throw new KnowledgeBaseException(503,"RAG_ANSWER_CAPACITY","Answer execution capacity is exhausted");
        AnswerTask task=null;
        try {
            var run=ledger.begin(actor,request.conversationId(),request.search().query(),requestId,Instant.now().plus(config.timeout()));
            task=new AnswerTask(actor,run,events,snapshot->ledger.checkpoint(actor,run.id(),snapshot));active.put(run.id(),task);var started=task;
            executor.execute(()->execute(started,request,streaming));return task;
        } catch(RuntimeException failure) {
            capacity.release();
            if(task!=null){active.remove(task.run.id());ledger.fail(actor,task.run.id(),"FAILED","RAG_ANSWER_CAPACITY","");}
            if(failure instanceof RejectedExecutionException)throw new KnowledgeBaseException(503,"RAG_ANSWER_CAPACITY","Answer execution capacity is exhausted");
            throw failure;
        }
    }
    public AnswerRunBO cancel(KnowledgeBasePrincipal actor,UUID id) {
        var run=ledger.cancel(actor,id,"CANCELLED","RAG_ANSWER_CANCELLED");
        var task=active.get(id);
        if(task!=null && run.status().equals("CANCELLED")){task.stop("CANCELLED");ledger.partial(actor,id,task.partial());}
        return ledger.get(actor,id);
    }
    public void disconnect(AnswerTask task) {if(task!=null && !task.completion.isDone())stop(task,"CANCELLED","RAG_CLIENT_DISCONNECTED");}
    public void timeout(AnswerTask task) {if(task!=null && !task.completion.isDone())stop(task,"TIMED_OUT","RAG_ANSWER_TIMEOUT");}
    private void stop(AnswerTask task,String status,String code) {
        // 先取消真实网络和线程；数据库 CAS 保证旧任务不能结算成功。
        task.stop(status);
        ledger.cancel(task.actor,task.run.id(),status,code);ledger.partial(task.actor,task.run.id(),task.partial());
    }
    private void execute(AnswerTask task,RagRetrieveBO request,boolean streaming) {
        AnswerRunBO settled=null;boolean persisted=false;
        try {
            task.events.meta(task.run);task.attach();
            var answer=pipeline.execute(task,request,streaming);task.check();
            ledger.finish(task.actor,task.run.id(),answer);
            settled=ledger.get(task.actor,task.run.id());persisted=true;task.completion.complete(answer);
        } catch(Exception failure) {
            String reason=task.reason();
            int http=failure instanceof KnowledgeBaseException kb?kb.status():500;
            String code=failure instanceof KnowledgeBaseException kb?kb.code():"RAG_INTERNAL";
            String status=reason!=null?reason:task.expired() || http==504?"TIMED_OUT":Thread.currentThread().isInterrupted()?"CANCELLED":"FAILED";
            if(status.equals("TIMED_OUT")){if(reason!=null || task.expired())code="RAG_ANSWER_TIMEOUT";http=504;}
            else if(status.equals("CANCELLED")){code="RAG_ANSWER_CANCELLED";http=503;}
            task.detach();
            try {
                ledger.fail(task.actor,task.run.id(),status,code,task.partial());
                ledger.partial(task.actor,task.run.id(),task.partial());
                settled=ledger.get(task.actor,task.run.id());persisted=true;
                if(settled.status().equals("COMPLETED"))task.completion.complete(settled.result());
                else task.completion.completeExceptionally(new AnswerFailure(http,settled.errorCode()==null?code:settled.errorCode(),settled));
            } catch(Exception persistence) {
                log.warn("rag_answer_persistence_failed requestId={} runId={}",task.run.requestId(),task.run.id());
                task.completion.completeExceptionally(new AnswerFailure(503,"RAG_RUN_PERSISTENCE_UNAVAILABLE",task.run));
            }
        } finally {
            task.detach();
            try {
                if(settled==null || !Set.of("COMPLETED","NO_EVIDENCE","NEEDS_CLARIFICATION").contains(settled.status()))
                    task.events.error(settled==null?"RAG_RUN_PERSISTENCE_UNAVAILABLE":settled.errorCode()==null?"RAG_RUN_FAILED":settled.errorCode());
                task.events.finish(settled==null?task.run:settled,persisted);task.events.done();
            } catch(RuntimeException disconnected) { /* 已落库的终态不依赖网络收尾成功。 */ }
            finally {active.remove(task.run.id());capacity.release();}
        }
    }
    private void tick() {
        for(var task:active.values())try {
            if(task.completion.isDone())continue;
            if(task.expired()){stop(task,"TIMED_OUT","RAG_ANSWER_TIMEOUT");continue;}
            var run=ledger.get(task.actor,task.run.id());
            if(Set.of("CANCELLED","TIMED_OUT").contains(run.status()))task.stop(run.status());
            if(!task.partial().isEmpty())ledger.partial(task.actor,task.run.id(),task.partial());
        } catch(Exception unavailable){log.warn("rag_answer_monitor_failed runId={}",task.run.id());}
        try {ledger.expireAll();}catch(Exception unavailable){log.warn("rag_answer_expiry_failed");}
    }
    @PreDestroy void close(){monitor.shutdownNow();for(var task:active.values())try{stop(task,"CANCELLED","RAG_SERVER_SHUTDOWN");}catch(Exception ignored){}executor.shutdown();}
}
