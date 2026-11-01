package com.lzj.rag.backend.rag.controller.support;

import com.lzj.rag.backend.knowledgebase.exception.KnowledgeBaseException;
import com.lzj.rag.backend.rag.config.InternalRagProperties;
import com.lzj.rag.backend.rag.controller.vo.AgentEvidenceVO;
import com.lzj.rag.backend.rag.repository.ToolRequestRepository;
import com.lzj.rag.backend.rag.service.RagRetrievalService;
import com.lzj.rag.backend.rag.service.impl.DelegationService;
import com.lzj.rag.backend.rag.service.bo.*;
import com.lzj.rag.backend.search.service.bo.SearchBO;
import com.lzj.rag.backend.security.context.RagTraceContext;
import com.lzj.rag.framework.starter.web.context.RequestIdContext;
import com.lzj.rag.framework.starter.web.result.Results;
import jakarta.annotation.PreDestroy;
import org.slf4j.*;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.async.DeferredResult;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** 内部请求生命周期只管理预算与取消；检索/精排/证据算法仍全部调用原门面。 */
@Component
public class InternalRetrievalExecution {
    private static final Logger LOG=LoggerFactory.getLogger(InternalRetrievalExecution.class);
    private final RagRetrievalService retrieval;
    private final DelegationService grants;
    private final ToolRequestRepository requests;
    private final ThreadPoolTaskExecutor workers=new ThreadPoolTaskExecutor();
    private final ConcurrentMap<String,Job> active=new ConcurrentHashMap<>();
    private final ScheduledExecutorService monitor=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"rag-tool-monitor");t.setDaemon(true);return t;});
    private int ticks;

    public InternalRetrievalExecution(RagRetrievalService retrieval,DelegationService grants,ToolRequestRepository requests,InternalRagProperties config) {
        this.retrieval=retrieval;this.grants=grants;this.requests=requests;
        workers.setCorePoolSize(config.concurrency());workers.setMaxPoolSize(config.concurrency());workers.setQueueCapacity(0);
        workers.setThreadNamePrefix("rag-tool-");workers.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());workers.initialize();
        monitor.scheduleWithFixedDelay(this::tick,1,1,TimeUnit.SECONDS);
    }
    public DeferredResult<ResponseEntity<?>> retrieve(DelegationBO grant,SearchBO request,String requestId,String trace,Instant deadline) {
        requests.begin(requestId,grant.id(),trace.substring(3,35),deadline);
        var job=new Job(grant,requestId,trace,deadline);active.put(requestId,job);
        job.result.onTimeout(()->job.stop(504,"RAG_TOOL_TIMEOUT"));
        try {
            job.future=workers.submit(()->run(job,request));
            if(job.settled.get())job.future.cancel(true);
        } catch(RuntimeException full){job.stop(503,"RAG_TOOL_CAPACITY");}
        return job.result;
    }
    public void cancel(DelegationBO grant,String requestId,String trace,Instant deadline) {
        requests.stop(requestId,grant.id(),trace.substring(3,35),deadline,"CANCELLED");
        var job=active.get(requestId);
        if(job!=null && job.grant.id().equals(grant.id()))job.stop(503,"RAG_TOOL_CANCELLED");
    }
    private void run(Job job,SearchBO input) {
        RequestIdContext.set(job.id);RagTraceContext.set(job.trace);
        try {
            if(job.settled.get())return;
            // 取消可能在持久记录建立后、任务句柄注册前到达其他实例。
            String state=requests.status(job.id,job.grant.id());
            if(!state.equals("RUNNING"))throw new KnowledgeBaseException(state.equals("TIMED_OUT")?504:503,
                    state.equals("TIMED_OUT")?"RAG_TOOL_TIMEOUT":"RAG_TOOL_CANCELLED","Retrieval no longer has execution authority");
            grants.recheck(job.grant);
            var scope=grants.scope(job.grant,input);
            var found=retrieval.retrieve(job.grant.actor(),new RagRetrieveBO(scope,null),job.id,RagRetrievalService.QueryMode.INDEPENDENT,job.deadline);
            grants.recheck(job.grant);
            if(!requests.finish(job.id,job.grant.id()))throw new KnowledgeBaseException(503,"RAG_TOOL_CANCELLED","Retrieval ended before completion");
            if(job.settled.compareAndSet(false,true))job.result.setResult(ResponseEntity.ok().header("Cache-Control","no-store")
                    .body(Results.success(AgentEvidenceVO.from(found,job.id,job.trace.substring(3,35)))));
        } catch(Exception error) {
            int status=error instanceof KnowledgeBaseException kb?kb.status():503;
            String code=error instanceof KnowledgeBaseException kb?kb.code():"RAG_RETRIEVAL_UNAVAILABLE";
            job.stop(status,code);
        } finally {
            active.remove(job.id,job);RequestIdContext.clear();RagTraceContext.set(null);Thread.interrupted();
        }
    }
    private void tick() {
        for(var job:active.values())try {
            if(job.settled.get())continue;
            if(Instant.now().isAfter(job.deadline)){job.stop(504,"RAG_TOOL_TIMEOUT");continue;}
            String state=requests.status(job.id,job.grant.id());
            if(!state.equals("RUNNING"))job.stop(state.equals("TIMED_OUT")?504:503,state.equals("TIMED_OUT")?"RAG_TOOL_TIMEOUT":"RAG_TOOL_CANCELLED");
            else grants.recheck(job.grant);
        } catch(Exception unavailable){job.stop(503,"RAG_DELEGATION_UNAVAILABLE");}
        if(++ticks%60==0)try{requests.cleanup();}catch(Exception ignored){LOG.warn("rag_tool_cleanup_unavailable");}
    }
    private final class Job {
        final DelegationBO grant;final String id,trace;final Instant deadline;
        final DeferredResult<ResponseEntity<?>> result;
        final AtomicBoolean settled=new AtomicBoolean();
        volatile Future<?> future;
        Job(DelegationBO grant,String id,String trace,Instant deadline){this.grant=grant;this.id=id;this.trace=trace;this.deadline=deadline;
            result=new DeferredResult<>(Math.max(1,Duration.between(Instant.now(),deadline).toMillis()));}
        void stop(int http,String code) {
            if(!settled.compareAndSet(false,true))return;
            var pending=future;if(pending!=null)pending.cancel(true);
            String requestId=RequestIdContext.get();RequestIdContext.set(id);
            try {
                var body=Results.failure(code,"Knowledge retrieval is unavailable; inspect the controlled status")
                        .setData(Map.of("status","UNAVAILABLE","requestId",id,"traceId",trace.substring(3,35),"retryable",http==503 || http==504));
                result.setResult(ResponseEntity.status(http).header("Cache-Control","no-store").body(body));
                // 结果先结束等待；即使持久依赖故障，仍已取消本机真实 HTTP/Future。
                boolean interrupted=Thread.interrupted();
                try{requests.stop(id,grant.id(),trace.substring(3,35),deadline,http==504?"TIMED_OUT":"CANCELLED");}
                catch(Exception unavailable){LOG.warn("rag_tool_settlement_unavailable requestId={} traceId={}",id,trace.substring(3,35));}
                finally{if(interrupted)Thread.currentThread().interrupt();}
            } finally {RequestIdContext.set(requestId);active.remove(id,this);}
        }
    }
    @PreDestroy void close(){monitor.shutdownNow();for(var job:active.values())job.stop(503,"RAG_TOOL_CANCELLED");workers.shutdown();}
}
