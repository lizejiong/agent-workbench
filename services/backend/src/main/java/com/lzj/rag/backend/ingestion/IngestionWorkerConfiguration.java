package com.lzj.rag.backend.ingestion;

import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.*;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration(proxyBeanMethods=false)
@EnableScheduling
public class IngestionWorkerConfiguration {
    @Bean public ThreadPoolTaskExecutor ingestionExecutor(IngestionWorkerProperties config) {
        var executor=new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(config.concurrency());executor.setMaxPoolSize(config.concurrency());
        executor.setQueueCapacity(config.queueCapacity());executor.setThreadNamePrefix("kb-ingestion-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);executor.setAwaitTerminationSeconds(5);
        return executor;
    }
    @Bean public ThreadPoolTaskScheduler taskScheduler() {
        var scheduler=new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(3);scheduler.setThreadNamePrefix("kb-scheduler-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        return scheduler;
    }
}
