package com.lzj.rag.backend.search;
import com.lzj.rag.backend.ingestion.IngestionWorkerProperties;
import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class KeywordSyncWorker {
    private final KeywordSyncService service;private final KeywordProperties config;private final IngestionWorkerProperties worker;
    private final AtomicBoolean busy=new AtomicBoolean();private final ThreadPoolTaskExecutor executor=new ThreadPoolTaskExecutor();
    public KeywordSyncWorker(KeywordSyncService service,KeywordProperties config,IngestionWorkerProperties worker){this.service=service;this.config=config;this.worker=worker;executor.setCorePoolSize(1);executor.setMaxPoolSize(1);executor.setQueueCapacity(0);executor.setThreadNamePrefix("kb-keyword-sync-");executor.setWaitForTasksToCompleteOnShutdown(false);executor.initialize();}
    @Scheduled(fixedDelayString="${kb.keyword.poll-delay:1000}",initialDelayString="${kb.worker.initial-delay:1000}")
    public void scan(){if(!config.enabled() || !worker.enabled() || !busy.compareAndSet(false,true))return;try{executor.execute(()->{try{service.runOnce();}catch(RuntimeException e){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Keyword outbox remains recoverable");}finally{busy.set(false);}});}catch(RuntimeException e){busy.set(false);}}
    @Scheduled(fixedDelay=2000) public void heartbeat(){if(config.enabled() && worker.enabled())try{service.heartbeat();}catch(RuntimeException e){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Keyword lease renewal unavailable; publication stays fenced");}}
    @PreDestroy void close(){executor.shutdown();}
}
