package com.lzj.rag.backend.ingestion.config;

import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.*;

import java.util.concurrent.ThreadPoolExecutor;

/** 配置有界入库执行器和调度线程，避免后台任务无限排队。 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class IngestionWorkerConfiguration {

    /**
     * 创建容量受限的入库线程池，提交过载时明确拒绝。
     *
     * @param config 入库工作线程、租约、重试退避和失效代次清理的配置。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @Bean
    public ThreadPoolTaskExecutor ingestionExecutor(IngestionWorkerProperties config) {
        // executor：容量受限的后台或检索执行器。
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(config.concurrency());
        executor.setMaxPoolSize(config.concurrency());
        executor.setQueueCapacity(config.queueCapacity());
        executor.setThreadNamePrefix("kb-ingestion-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setAwaitTerminationSeconds(5);
        return executor;
    }

    /**
     * 创建业务后台扫描与心跳使用的调度线程池。
     *
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        // scheduler：执行定时任务及子进程监控的调度器。
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(3);
        scheduler.setThreadNamePrefix("kb-scheduler-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        return scheduler;
    }
}
