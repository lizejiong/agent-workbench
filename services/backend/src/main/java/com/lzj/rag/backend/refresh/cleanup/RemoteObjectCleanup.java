package com.lzj.rag.backend.refresh.cleanup;

import com.lzj.rag.backend.document.storage.DocumentStorage;
import com.lzj.rag.backend.infra.storage.config.StorageProperties;
import com.lzj.rag.backend.ingestion.config.IngestionWorkerProperties;
import com.lzj.rag.backend.knowledgebase.exception.KnowledgeBaseException;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/** 通过持久化清理队列回收远程刷新遗留对象，失败后保留重试状态。 */
@Service
public class RemoteObjectCleanup {

    /** 执行数据库查询及受条件保护的业务状态更新。 */
    private final JdbcTemplate jdbc;

    /** 支持流式读写及幂等删除的源对象存储组件。 */
    private final DocumentStorage storage;

    /** 对象存储连接、文件容量、临时目录及上传补偿配置。 */
    private final StorageProperties storageConfig;

    /** 入库工作线程、租约、重试退避和失效代次清理的配置。 */
    private final IngestionWorkerProperties worker;

    /** 用于短时状态变更的事务模板。 */
    private final TransactionTemplate tx;

    /** 当前服务实例的后台任务领取标识。 */
    private final String instance = "remote-gc-" + UUID.randomUUID();

    /**
     * 初始化当前组件，保存业务依赖及本次使用的配置。
     *
     * @param jdbc 执行数据库查询及受条件保护的业务状态更新。
     * @param storage 支持流式读写及幂等删除的源对象存储组件。
     * @param storageConfig 对象存储连接、文件容量、临时目录及上传补偿配置。
     * @param worker 入库工作线程、租约、重试退避和失效代次清理的配置。
     * @param transactions 创建后台短事务及只读事务的事务管理器。
     */
    public RemoteObjectCleanup(
            JdbcTemplate jdbc,
            DocumentStorage storage,
            StorageProperties storageConfig,
            IngestionWorkerProperties worker,
            PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.storageConfig = storageConfig;
        this.worker = worker;
        tx = new TransactionTemplate(transactions);
        tx.setTimeout(10);
    }

    /** 扫描待回收的远程源对象并逐项执行清理。 */
    @Scheduled(
            fixedDelayString = "${kb.remote.cleanup-poll-delay:10000}",
            initialDelayString = "${kb.worker.initial-delay:1000}")
    public void scan() {
        if (!worker.enabled()) return;
        try {
            runOnce();
        } catch (RuntimeException error) {
            // error：本次处理捕获的异常，用于归类失败原因。
            org.slf4j.LoggerFactory.getLogger(getClass())
                    .warn("Remote object cleanup deferred; durable intent remains pending");
        }
    }

    /**
     * 领取对象清理租约，确认对象不再被引用后删除并提交执行结果。
     *
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public boolean runOnce() {
        // claim：当前后台任务的租约凭据或领取记录。
        var claim =
                tx.execute(
                        status -> {
                            // status：本次短事务的执行状态，由事务模板传入。
                            jdbc.update(
                                    """
                UPDATE kb_remote_object_cleanup c SET status='PENDING' WHERE status='WAITING' AND next_run_at<=clock_timestamp()
                AND NOT EXISTS(SELECT 1 FROM kb_refresh_run r WHERE r.id=c.run_id AND r.status IN ('PENDING','RUNNING','WAITING_INGESTION') AND r.candidate_object_key=c.object_key)
                """);
                            // c：当前待领取或待收敛的持久化任务记录。
                            for (var c :
                                    jdbc.queryForList(
                                            "SELECT id,kb_id,document_id FROM kb_remote_object_cleanup WHERE (status='PENDING' AND next_run_at<=clock_timestamp()) OR (status='RUNNING' AND lease_until<=clock_timestamp()) ORDER BY next_run_at,id LIMIT 32")) {
                                if (jdbc.queryForList(
                                                        "SELECT id FROM kb_knowledge_base WHERE id=? FOR UPDATE SKIP LOCKED",
                                                        UUID.class,
                                                        c.get("kb_id"))
                                                .isEmpty()
                                        || jdbc.queryForList(
                                                        "SELECT id FROM kb_document WHERE id=? FOR UPDATE SKIP LOCKED",
                                                        UUID.class,
                                                        c.get("document_id"))
                                                .isEmpty()) continue;
                                // rows：数据库结果或当前表格数据行集合。
                                var rows =
                                        jdbc.queryForList(
                                                "SELECT * FROM kb_remote_object_cleanup WHERE id=? AND ((status='PENDING' AND next_run_at<=clock_timestamp()) OR (status='RUNNING' AND lease_until<=clock_timestamp())) FOR UPDATE SKIP LOCKED",
                                                c.get("id"));
                                if (rows.isEmpty()) continue;
                                // row：当前数据库记录或表格数据行。
                                var row = rows.get(0);
                                if (((Number) row.get("attempts")).intValue()
                                        >= worker.cleanupMaxAttempts()) {
                                    jdbc.update(
                                            "UPDATE kb_remote_object_cleanup SET status='FAILED',error_code='KB_CLEANUP_ATTEMPTS_EXHAUSTED',lease_owner=NULL,lease_until=NULL WHERE id=?",
                                            c.get("id"));
                                    continue;
                                }
                                if (Boolean.TRUE.equals(
                                        jdbc.queryForObject(
                                                "SELECT EXISTS(SELECT 1 FROM kb_document WHERE object_key=? AND deleted_at IS NULL) OR EXISTS(SELECT 1 FROM kb_refresh_run WHERE candidate_object_key=? AND status IN ('PENDING','RUNNING','WAITING_INGESTION'))",
                                                Boolean.class,
                                                row.get("object_key"),
                                                row.get("object_key")))) continue;
                                return jdbc.queryForMap(
                                        "UPDATE kb_remote_object_cleanup SET status='RUNNING',attempts=attempts+1,claim_token=claim_token+1,lease_owner=?,lease_until=clock_timestamp()+(?*interval '1 millisecond') WHERE id=? RETURNING *",
                                        instance,
                                        storageConfig
                                                .apiTimeout()
                                                .multipliedBy(2)
                                                .plusSeconds(30)
                                                .toMillis(),
                                        c.get("id"));
                            }
                            return null;
                        });
        if (claim == null) return false;
        // code：对外使用的稳定业务错误分类。
        String code = null;
        try {
            storage.delete((String) claim.get("object_key"));
        } catch (RuntimeException error) {
            // kb：本次捕获或匹配的异常，用于保留业务分类及失败原因。
            // error：本次处理捕获的异常，用于归类失败原因。
            code =
                    error instanceof KnowledgeBaseException kb
                            ? kb.code()
                            : "KB_STORAGE_UNAVAILABLE";
        }
        // errorCode：可供调用方和运维识别的稳定错误码。
        final String errorCode = code;
        tx.executeWithoutResult(
                status -> {
                    // status：本次短事务的执行状态，由事务模板传入。
                    jdbc.queryForObject(
                            "SELECT id FROM kb_knowledge_base WHERE id=? FOR UPDATE",
                            UUID.class,
                            claim.get("kb_id"));
                    jdbc.queryForObject(
                            "SELECT id FROM kb_document WHERE id=? FOR UPDATE",
                            UUID.class,
                            claim.get("document_id"));
                    // terminal：任务终态或是否已进入终态。
                    String terminal =
                            errorCode == null
                                    ? "SUCCEEDED"
                                    : ((Number) claim.get("attempts")).intValue()
                                                    >= worker.cleanupMaxAttempts()
                                            ? "FAILED"
                                            : "PENDING";
                    jdbc.update(
                            "UPDATE kb_remote_object_cleanup SET status=?,error_code=?,completed_at=CASE WHEN ?='SUCCEEDED' THEN clock_timestamp() ELSE NULL END,next_run_at=clock_timestamp()+(?*interval '1 millisecond'),lease_owner=NULL,lease_until=NULL WHERE id=? AND status='RUNNING' AND claim_token=? AND lease_owner=? AND lease_until>clock_timestamp()",
                            terminal,
                            errorCode,
                            terminal,
                            worker.backoffMillis(((Number) claim.get("attempts")).intValue()),
                            claim.get("id"),
                            claim.get("claim_token"),
                            instance);
                });
        return true;
    }
}
