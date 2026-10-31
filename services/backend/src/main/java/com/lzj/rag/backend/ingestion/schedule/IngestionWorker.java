package com.lzj.rag.backend.ingestion.schedule;

import com.lzj.rag.backend.ingestion.config.IngestionWorkerProperties;
import com.lzj.rag.backend.ingestion.pipeline.IngestionKernel;
import com.lzj.rag.backend.ingestion.service.IngestionJobService;
import com.lzj.rag.backend.ingestion.service.bo.IngestionContextBO;

import org.slf4j.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;

/** 以有界线程池执行持久化入库任务，并定时续租仍在运行的尝试。 */
@Component
public class IngestionWorker {

    /** 当前组件使用的日志记录器。 */
    private static final Logger LOG = LoggerFactory.getLogger(IngestionWorker.class);

    /**
     * 运行中的尝试上下文及对应可取消任务。
     *
     * @param context 本次入库尝试固定的输入快照及租约凭据。
     * @param task 当前异步操作的可等待结果，超时或失去执行权时用于取消任务。
     */
    private record Execution(IngestionContextBO context, FutureTask<Void> task) {}

    /** 负责持久化入库任务及租约状态迁移的业务服务。 */
    private final IngestionJobService jobs;

    /** 执行读取、解析、切片、嵌入与发布的入库流水线。 */
    private final IngestionKernel kernel;

    /** 入库工作线程、租约、重试退避和失效代次清理的配置。 */
    private final IngestionWorkerProperties config;

    /** 容量受限的业务任务执行器。 */
    private final ThreadPoolTaskExecutor executor;

    /** 限制当前业务并发执行数量的许可集合。 */
    private final Semaphore capacity;

    /** 按任务标识跟踪本实例正在运行的尝试。 */
    private final ConcurrentMap<UUID, Execution> running = new ConcurrentHashMap<>();

    /**
     * 初始化当前组件，保存业务依赖及本次使用的配置。
     *
     * @param jobs 负责持久化入库任务及租约状态迁移的业务服务。
     * @param kernel 执行读取、解析、切片、嵌入与发布的入库流水线。
     * @param config 入库工作线程、租约、重试退避和失效代次清理的配置。
     * @param executor 容量受限的业务任务执行器。
     */
    public IngestionWorker(
            IngestionJobService jobs,
            IngestionKernel kernel,
            IngestionWorkerProperties config,
            @Qualifier("ingestionExecutor") ThreadPoolTaskExecutor executor) {
        this.jobs = jobs;
        this.kernel = kernel;
        this.config = config;
        this.executor = executor;
        capacity = new Semaphore(config.concurrency() + config.queueCapacity());
    }

    /** 按本实例剩余并发槽位领取任务，提交失败时释放尚未开始的租约。 */
    @Scheduled(
            fixedDelayString = "${kb.worker.poll-delay:1000}",
            initialDelayString = "${kb.worker.initial-delay:1000}")
    public void scan() {
        if (!config.enabled()) return;
        while (capacity.tryAcquire()) {
            // context：本次入库尝试固定的输入快照及租约凭据。
            IngestionContextBO context = null;
            try {
                context = jobs.claimNext();
                if (context == null) {
                    capacity.release();
                    return;
                }
                // claimed：成功领取后携带有效执行凭据的任务记录。
                final var claimed = context;
                // task：本次已提交的异步任务，用于等待、取消或跟踪完成情况。
                var task =
                        new FutureTask<Void>(
                                () -> {
                                    kernel.execute(claimed);
                                    return null;
                                });
                // execution：当前运行尝试及其可取消执行任务。
                var execution = new Execution(claimed, task);
                running.put(claimed.jobId(), execution);
                // 许可在线程实际退出时释放；FutureTask 被取消时，底层线程可能尚未结束。
                executor.execute(
                        () -> {
                            try {
                                task.run();
                                task.get();
                            } catch (CancellationException ignored) {
                            } catch (InterruptedException e) {
                                // e：本次捕获或匹配的异常，用于保留业务分类及失败原因。
                                Thread.currentThread().interrupt();
                            } catch (ExecutionException e) {
                                // e：本次捕获或匹配的异常，用于保留业务分类及失败原因。
                                LOG.warn(
                                        "Ingestion execution ended jobId={} attempt={}",
                                        claimed.jobId(),
                                        claimed.attempt());
                            } finally {
                                running.remove(claimed.jobId(), execution);
                                capacity.release();
                            }
                        });
            } catch (RuntimeException error) {
                // error：本次处理捕获的异常，用于归类失败原因。
                if (context != null) {
                    running.remove(context.jobId());
                    try {
                        jobs.releaseUnstarted(context);
                    } catch (RuntimeException ignored) {
                        // 释放失败时保留持久化领取记录，由租约到期后的扫描恢复。
                        LOG.warn("Unstarted claim awaits lease recovery jobId={}", context.jobId());
                    }
                }
                capacity.release();
                LOG.warn("Ingestion scan paused; persistent jobs remain recoverable");
                return;
            }
        }
    }

    /** 为运行任务续租；失去执行权时取消对应工作线程。 */
    @Scheduled(fixedDelayString = "${kb.worker.heartbeat:5s}")
    public void heartbeat() {
        if (!config.enabled()) return;
        // execution：当前运行尝试及其可取消执行任务。
        for (var execution : running.values()) {
            try {
                if (!jobs.renew(execution.context())) execution.task().cancel(true);
            } catch (RuntimeException ignored) {
                // 续租失败时记录告警，任务的执行权仍受数据库租约和发布栅栏约束。
                LOG.warn("Lease renewal unavailable jobId={}", execution.context().jobId());
            }
        }
    }
}
