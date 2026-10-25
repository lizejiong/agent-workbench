package com.lzj.rag.backend.refresh;
import com.lzj.rag.backend.ingestion.IngestionWorkerProperties;
import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import java.util.concurrent.*;

@Component
public class RefreshWorker {
    private final RefreshService service;private final RemoteProperties config;private final IngestionWorkerProperties worker;
    private final ThreadPoolTaskExecutor executor;private final Semaphore slots;
    public RefreshWorker(RefreshService service,RemoteProperties config,IngestionWorkerProperties worker) {
        this.service=service;this.config=config;this.worker=worker;slots=new Semaphore(config.concurrency());
        executor=new ThreadPoolTaskExecutor();executor.setCorePoolSize(config.concurrency());executor.setMaxPoolSize(config.concurrency());
        executor.setQueueCapacity(0);executor.setThreadNamePrefix("kb-refresh-");executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);executor.setAwaitTerminationSeconds(5);executor.initialize();
    }
    @Scheduled(fixedDelayString="${kb.remote.poll-delay:1000}",initialDelayString="${kb.worker.initial-delay:1000}")
    public void scan() {
        if(!worker.enabled() || !config.enabled())return;
        try {
            service.reconcile();service.enqueueDue();
            while(slots.tryAcquire()) {
                RefreshService.Claim claim;
                try{claim=service.claimNext();}catch(RuntimeException error){slots.release();throw error;}
                if(claim==null){slots.release();return;}
                try{executor.execute(()->{try{service.execute(claim);}finally{slots.release();}});}
                catch(RuntimeException rejected){slots.release();return;} // Durable unstarted lease is recovered, never dropped.
            }
        }catch(RuntimeException error){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Remote scan deferred; durable refresh state remains recoverable");}
    }
    @Scheduled(fixedDelayString="${kb.remote.heartbeat:5000}")
    public void heartbeat(){if(worker.enabled() && config.enabled())try{service.heartbeat();}catch(RuntimeException error){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Remote lease renewal unavailable; publication remains fenced");}}
    @PreDestroy void close(){executor.shutdown();}
}
