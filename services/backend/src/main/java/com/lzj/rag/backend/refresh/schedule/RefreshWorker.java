package com.lzj.rag.backend.refresh.schedule;

import com.lzj.rag.backend.ingestion.config.IngestionWorkerProperties;
import com.lzj.rag.backend.refresh.config.RemoteProperties;
import com.lzj.rag.backend.refresh.service.RefreshService;
import com.lzj.rag.backend.refresh.service.bo.RefreshClaimBO;

import jakarta.annotation.PreDestroy;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.concurrent.*;

/** 以有界执行器运行远程刷新，并定时续租和收敛任务状态。 */
@Component
public class RefreshWorker {

    /** 负责刷新计划、运行领取及条件下载的业务服务。 */
    private final RefreshService service;

    /** 远程导入的网络许可、下载容量、并发、租约和重试配置。 */
    private final RemoteProperties config;

    /** 入库工作线程、租约、重试退避和失效代次清理的配置。 */
    private final IngestionWorkerProperties worker;

    /** 容量受限的业务任务执行器。 */
    private final ThreadPoolTaskExecutor executor;

    /** 限制当前业务并发执行数量的许可集合。 */
    private final Semaphore slots;

    /**
     * 初始化当前组件，保存业务依赖及本次使用的配置。
     *
     * @param service 负责刷新计划、运行领取及条件下载的业务服务。
     * @param config 远程导入的网络许可、下载容量、并发、租约和重试配置。
     * @param worker 入库工作线程、租约、重试退避和失效代次清理的配置。
     */
    public RefreshWorker(
            RefreshService service, RemoteProperties config, IngestionWorkerProperties worker) {
        this.service = service;
        this.config = config;
        this.worker = worker;
        slots = new Semaphore(config.concurrency());
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(config.concurrency());
        executor.setMaxPoolSize(config.concurrency());
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("kb-refresh-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setAwaitTerminationSeconds(5);
        executor.initialize();
    }

    /** 按可用并发槽位领取持久化任务，并提交到有界工作线程。 */
    @Scheduled(
            fixedDelayString = "${kb.remote.poll-delay:1000}",
            initialDelayString = "${kb.worker.initial-delay:1000}")
    public void scan() {
        if (!worker.enabled() || !config.enabled()) return;
        try {
            service.reconcile();
            service.enqueueDue();
            while (slots.tryAcquire()) {
                // claim：当前后台任务的租约凭据或领取记录。
                RefreshClaimBO claim;
                try {
                    claim = service.claimNext();
                } catch (RuntimeException error) {
                    // error：本次处理捕获的异常，用于归类失败原因。
                    slots.release();
                    throw error;
                }
                if (claim == null) {
                    slots.release();
                    return;
                }
                try {
                    executor.execute(
                            () -> {
                                try {
                                    service.execute(claim);
                                } finally {
                                    slots.release();
                                }
                            });
                } // Durable unstarted lease is recovered, never dropped.
                catch (RuntimeException rejected) {
                    // rejected：线程池拒绝执行产生的异常。
                    slots.release();
                    return;
                }
            }
        } catch (RuntimeException error) {
            // error：本次处理捕获的异常，用于归类失败原因。
            org.slf4j.LoggerFactory.getLogger(getClass())
                    .warn("Remote scan deferred; durable refresh state remains recoverable");
        }
    }

    /** 为本实例仍在运行的有效任务续租。 */
    @Scheduled(fixedDelayString = "${kb.remote.heartbeat:5000}")
    public void heartbeat() {
        if (worker.enabled() && config.enabled())
            try {
                service.heartbeat();
            } catch (RuntimeException error) {
                // error：本次处理捕获的异常，用于归类失败原因。
                org.slf4j.LoggerFactory.getLogger(getClass())
                        .warn("Remote lease renewal unavailable; publication remains fenced");
            }
    }

    /** 停止接收后台任务并中断执行器中尚未完成的工作。 */
    @PreDestroy
    void close() {
        executor.shutdown();
    }
}
