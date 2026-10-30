package com.lzj.rag.backend.ingestion.schedule;

import com.lzj.rag.backend.ingestion.cleanup.GenerationCleanupService;
import com.lzj.rag.backend.ingestion.config.IngestionWorkerProperties;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 定时扫描待回收的历史代次，逐项执行清理。 */
@Component
public class GenerationCleanupScheduler {

    /** 负责失效代次资源回收的业务服务。 */
    private final GenerationCleanupService cleanup;

    /** 入库工作线程、租约、重试退避和失效代次清理的配置。 */
    private final IngestionWorkerProperties config;

    /**
     * 初始化当前组件，保存业务依赖及本次使用的配置。
     *
     * @param cleanup 负责失效代次资源回收的业务服务。
     * @param config 入库工作线程、租约、重试退避和失效代次清理的配置。
     */
    public GenerationCleanupScheduler(
            GenerationCleanupService cleanup, IngestionWorkerProperties config) {
        this.cleanup = cleanup;
        this.config = config;
    }

    /** 定时批量扫描并清理失效代次，记录单项失败以便后续重试。 */
    @Scheduled(
            fixedDelayString = "${kb.worker.cleanup-poll-delay:10000}",
            initialDelayString = "${kb.worker.initial-delay:1000}")
    public void scan() {
        if (!config.enabled()) return;
        try {
            // id：当前业务对象的唯一标识。
            for (var id : cleanup.due())
                try {
                    cleanup.clean(id);
                } catch (RuntimeException error) {
                    // error：本次处理捕获的异常，用于归类失败原因。
                    cleanup.failed(id);
                }
        } catch (RuntimeException ignored) {
            // ignored：回收或补偿过程中捕获的异常，不覆盖原业务处理结果。
            org.slf4j.LoggerFactory.getLogger(getClass())
                    .warn("Generation cleanup paused; persistent records remain recoverable");
        }
    }
}
