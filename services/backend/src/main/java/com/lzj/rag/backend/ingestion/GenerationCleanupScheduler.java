package com.lzj.rag.backend.ingestion;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class GenerationCleanupScheduler {
    private final GenerationCleanupService cleanup;private final IngestionWorkerProperties config;
    public GenerationCleanupScheduler(GenerationCleanupService cleanup,IngestionWorkerProperties config){this.cleanup=cleanup;this.config=config;}
    @Scheduled(fixedDelayString="${kb.worker.cleanup-poll-delay:10000}",initialDelayString="${kb.worker.initial-delay:1000}")
    public void scan() {
        if(!config.enabled())return;
        try {
            for(var id:cleanup.due())try{cleanup.clean(id);}catch(RuntimeException error){cleanup.failed(id);}
        } catch(RuntimeException ignored){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Generation cleanup paused; persistent records remain recoverable");}
    }
}
