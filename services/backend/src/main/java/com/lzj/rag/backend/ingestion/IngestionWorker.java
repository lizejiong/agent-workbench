package com.lzj.rag.backend.ingestion;

import org.slf4j.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.*;

/** In-memory slots only limit capacity. All queued work and execution rights live in PostgreSQL. */
@Component
public class IngestionWorker {
    private static final Logger LOG=LoggerFactory.getLogger(IngestionWorker.class);
    private record Execution(IngestionContext context,FutureTask<Void> task) { }
    private final IngestionJobService jobs;private final IngestionKernel kernel;private final IngestionWorkerProperties config;
    private final ThreadPoolTaskExecutor executor;private final Semaphore capacity;
    private final ConcurrentMap<UUID,Execution> running=new ConcurrentHashMap<>();
    public IngestionWorker(IngestionJobService jobs,IngestionKernel kernel,IngestionWorkerProperties config,@Qualifier("ingestionExecutor") ThreadPoolTaskExecutor executor) {
        this.jobs=jobs;this.kernel=kernel;this.config=config;this.executor=executor;
        capacity=new Semaphore(config.concurrency()+config.queueCapacity());
    }
    @Scheduled(fixedDelayString="${kb.worker.poll-delay:1000}",initialDelayString="${kb.worker.initial-delay:1000}")
    public void scan() {
        if(!config.enabled())return;
        while(capacity.tryAcquire()) {
            IngestionContext context=null;
            try {
                context=jobs.claimNext();
                if(context==null){capacity.release();return;}
                final var claimed=context;
                var task=new FutureTask<Void>(()->{kernel.execute(claimed);return null;});
                var execution=new Execution(claimed,task);
                running.put(claimed.jobId(),execution);
                // Release in the runnable's finally, not FutureTask.done: cancellation may precede actual thread exit.
                executor.execute(()->{
                    try { task.run();task.get(); }
                    catch(CancellationException ignored){ }
                    catch(InterruptedException e){Thread.currentThread().interrupt();}
                    catch(ExecutionException e){LOG.warn("Ingestion execution ended jobId={} attempt={}",claimed.jobId(),claimed.attempt());}
                    finally {running.remove(claimed.jobId(),execution);capacity.release();}
                });
            } catch(RuntimeException error) {
                if(context!=null){running.remove(context.jobId());try{jobs.releaseUnstarted(context);}catch(RuntimeException ignored){LOG.warn("Unstarted claim awaits lease recovery jobId={}",context.jobId());}}
                capacity.release();LOG.warn("Ingestion scan paused; persistent jobs remain recoverable");return;
            }
        }
    }
    @Scheduled(fixedDelayString="${kb.worker.heartbeat:5s}")
    public void heartbeat() {
        if(!config.enabled())return;
        for(var execution:running.values()) {
            try {if(!jobs.renew(execution.context()))execution.task().cancel(true);}
            catch(RuntimeException ignored){LOG.warn("Lease renewal unavailable jobId={}",execution.context().jobId());}
        }
    }
}
