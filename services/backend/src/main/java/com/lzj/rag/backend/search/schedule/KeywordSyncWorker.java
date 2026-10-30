package com.lzj.rag.backend.search.schedule;

import com.lzj.rag.backend.ingestion.config.IngestionWorkerProperties;
import com.lzj.rag.backend.search.config.KeywordProperties;
import com.lzj.rag.backend.search.service.KeywordSyncService;

import jakarta.annotation.PreDestroy;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/** 调度关键词同步执行和心跳续租，限制并行任务数量。 */
@Component
public class KeywordSyncWorker {

    /** 负责关键词同步任务及索引版本发布的业务服务。 */
    private final KeywordSyncService service;

    /** 关键词检索开关、索引身份、分析器及同步工作线程配置。 */
    private final KeywordProperties config;

    /** 入库工作线程、租约、重试退避和失效代次清理的配置。 */
    private final IngestionWorkerProperties worker;

    /** 用于防止同一调度过程重叠执行的进程内标记。 */
    private final AtomicBoolean busy = new AtomicBoolean();

    /** 容量受限的业务任务执行器。 */
    private final ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

    /**
     * 初始化当前组件，保存业务依赖及本次使用的配置。
     *
     * @param service 负责关键词同步任务及索引版本发布的业务服务。
     * @param config 关键词检索开关、索引身份、分析器及同步工作线程配置。
     * @param worker 入库工作线程、租约、重试退避和失效代次清理的配置。
     */
    public KeywordSyncWorker(
            KeywordSyncService service,
            KeywordProperties config,
            IngestionWorkerProperties worker) {
        this.service = service;
        this.config = config;
        this.worker = worker;
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("kb-keyword-sync-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
    }

    /** 按可用并发槽位领取持久化任务，并提交到有界工作线程。 */
    @Scheduled(
            fixedDelayString = "${kb.keyword.poll-delay:1000}",
            initialDelayString = "${kb.worker.initial-delay:1000}")
    public void scan() {
        if (!config.enabled() || !worker.enabled() || !busy.compareAndSet(false, true)) return;
        try {
            executor.execute(
                    () -> {
                        try {
                            service.runOnce();
                        } catch (RuntimeException e) {
                            // e：本次捕获或匹配的异常，用于保留业务分类及失败原因。
                            org.slf4j.LoggerFactory.getLogger(getClass())
                                    .warn("Keyword outbox remains recoverable");
                        } finally {
                            busy.set(false);
                        }
                    });
        } catch (RuntimeException e) {
            // e：本次捕获或匹配的异常，用于保留业务分类及失败原因。
            busy.set(false);
        }
    }

    /** 为本实例仍在运行的有效任务续租。 */
    @Scheduled(fixedDelay = 2000)
    public void heartbeat() {
        if (config.enabled() && worker.enabled())
            try {
                service.heartbeat();
            } catch (RuntimeException e) {
                // e：本次捕获或匹配的异常，用于保留业务分类及失败原因。
                org.slf4j.LoggerFactory.getLogger(getClass())
                        .warn("Keyword lease renewal unavailable; publication stays fenced");
            }
    }

    /** 停止接收后台任务并中断执行器中尚未完成的工作。 */
    @PreDestroy
    void close() {
        executor.shutdown();
    }
}
