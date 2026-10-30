package com.lzj.rag.backend.refresh.state;

import com.lzj.rag.backend.infra.storage.config.StorageProperties;
import com.lzj.rag.backend.ingestion.service.bo.IngestionContextBO;
import com.lzj.rag.backend.knowledgebase.exception.KnowledgeBaseException;
import com.lzj.rag.backend.operations.logging.OperationLog;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.time.*;
import java.util.*;

/** 集中维护刷新状态及对象切换规则；成功发布前不替换当前源对象。 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class RefreshState {

    /** 执行数据库查询及受条件保护的业务状态更新。 */
    private final JdbcTemplate jdbc;

    /** 对象存储连接、文件容量、临时目录及上传补偿配置。 */
    private final StorageProperties storage;

    /**
     * 初始化当前组件，保存业务依赖及本次使用的配置。
     *
     * @param jdbc 执行数据库查询及受条件保护的业务状态更新。
     * @param storage 对象存储连接、文件容量、临时目录及上传补偿配置。
     */
    public RefreshState(JdbcTemplate jdbc, StorageProperties storage) {
        this.jdbc = jdbc;
        this.storage = storage;
    }

    /**
     * 幂等创建文档的刷新计划记录。
     *
     * @param document 本次操作涉及的文档唯一标识。
     */
    public void ensure(UUID document) {
        jdbc.update(
                "INSERT INTO kb_refresh_schedule(document_id) VALUES (?) ON CONFLICT DO NOTHING",
                document);
    }

    /**
     * 锁定并检查刷新租约凭据、有效期及当前输入快照。
     *
     * @param run 当前刷新运行记录或运行标识。
     * @param token 当前任务的租约栅栏令牌或清理凭据。
     * @param owner 数据所有者或当前租约持有实例的标识。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public Map<String, Object> requireClaim(UUID run, long token, String owner) {
        // rows：数据库结果或当前表格数据行集合。
        var rows =
                jdbc.queryForList(
                        """
            SELECT r.* FROM kb_refresh_run r JOIN kb_refresh_schedule s ON s.document_id=r.document_id
            JOIN kb_document d ON d.id=r.document_id JOIN kb_knowledge_base b ON b.id=d.kb_id
            WHERE r.id=? AND r.claim_token=? AND r.lease_owner=? AND r.status IN ('RUNNING','WAITING_INGESTION')
            AND r.lease_until>clock_timestamp() AND r.deadline>clock_timestamp()
            AND s.claim_token=r.claim_token AND s.lease_owner=r.lease_owner AND s.lease_until>clock_timestamp()
            AND s.version=r.schedule_version AND d.version=r.document_version AND d.config_revision=r.config_revision
            AND d.enabled AND d.deleted_at IS NULL AND b.deleted_at IS NULL
            """,
                        run,
                        token,
                        owner);
        if (rows.isEmpty())
            throw new KnowledgeBaseException(
                    409,
                    "KB_REFRESH_CLAIM_LOST",
                    "Refresh lease, document or configuration changed");
        return rows.get(0);
    }

    /**
     * 发布入库结果前复核刷新运行和候选对象仍与当前任务匹配。
     *
     * @param context 本次入库尝试固定的输入快照及租约凭据。
     */
    public void beforePublish(IngestionContextBO context) {
        // jobs：当前加载的任务记录集合或入库任务服务。
        var jobs =
                jdbc.queryForList(
                        "SELECT refresh_run_id,refresh_claim_token FROM kb_ingestion_job WHERE id=?",
                        context.jobId());
        // job：当前入库任务记录或任务标识。
        var job = jobs.get(0);
        if (job.get("refresh_run_id") == null) return;
        // row：当前数据库记录或表格数据行。
        var row =
                jdbc.queryForMap(
                        "SELECT * FROM kb_refresh_run WHERE id=? FOR UPDATE",
                        job.get("refresh_run_id"));
        // run：当前刷新运行记录或运行标识。
        var run =
                requireClaim(
                        (UUID) row.get("id"),
                        ((Number) job.get("refresh_claim_token")).longValue(),
                        (String) row.get("lease_owner"));
        if (!context.jobId().equals(run.get("job_id"))
                || !"WAITING_INGESTION".equals(run.get("status")))
            throw new KnowledgeBaseException(
                    409, "KB_REFRESH_CLAIM_LOST", "Refresh no longer owns this ingestion job");
    }

    /**
     * 在入库发布事务中切换源对象及条件请求基线，并登记旧对象回收。
     *
     * @param context 本次入库尝试固定的输入快照及租约凭据。
     */
    public void published(IngestionContextBO context) {
        // ids：本次操作涉及的业务对象标识集合。
        var ids =
                jdbc.queryForList(
                        "SELECT refresh_run_id FROM kb_ingestion_job WHERE id=? AND refresh_run_id IS NOT NULL",
                        UUID.class,
                        context.jobId());
        if (ids.isEmpty()) {
            // imports：远程导入业务服务或匹配当前幂等键的导入记录。
            var imports =
                    jdbc.queryForList(
                            "SELECT * FROM kb_url_import WHERE document_id=? AND status='COMPLETED' AND object_key=?",
                            context.documentId(),
                            context.objectKey());
            if (!imports.isEmpty()) {
                ensure(context.documentId());
                // source：当前结构块的来源字段或已校验临时源文件。
                var source = imports.get(0);
                jdbc.update(
                        "UPDATE kb_refresh_schedule SET last_content_hash=?,last_etag=?,last_modified=?,last_status='SUCCESS',last_reason='INITIAL_IMPORT_ACTIVATED' WHERE document_id=? AND last_content_hash IS NULL",
                        context.contentHash(),
                        source.get("etag"),
                        source.get("last_modified"),
                        context.documentId());
            }
            return;
        }
        // run：当前刷新运行记录或运行标识。
        var run =
                jdbc.queryForMap("SELECT * FROM kb_refresh_run WHERE id=? FOR UPDATE", ids.get(0));
        requireClaim(
                ids.get(0),
                ((Number) run.get("claim_token")).longValue(),
                (String) run.get("lease_owner"));
        // oldKey：切换前的源对象键，发布成功后登记回收。
        String oldKey =
                jdbc.queryForObject(
                        "SELECT object_key FROM kb_document WHERE id=?",
                        String.class,
                        context.documentId());
        jdbc.update(
                """
            UPDATE kb_document SET object_key=?,original_filename=?,mime_type=?,file_size=?,content_hash=?,version=version+1 WHERE id=?
            """,
                run.get("candidate_object_key"),
                run.get("candidate_filename"),
                run.get("candidate_mime"),
                run.get("candidate_size"),
                run.get("candidate_hash"),
                context.documentId());
        jdbc.update(
                "UPDATE kb_refresh_schedule SET last_content_hash=?,last_etag=?,last_modified=? WHERE document_id=?",
                run.get("candidate_hash"),
                run.get("candidate_etag"),
                run.get("candidate_last_modified"),
                context.documentId());
        jdbc.update(
                "UPDATE kb_remote_object_cleanup SET status='RETAINED' WHERE object_key=? AND status='WAITING'",
                run.get("candidate_object_key"));
        queueObject(context.documentId(), context.kbId(), oldKey, null);
        finish(run, "SUCCESS", "CONTENT_ACTIVATED");
    }

    /**
     * 先持久化候选对象键，确保上传失败或进程退出后仍能追踪清理。
     *
     * @param run 当前刷新运行记录或运行标识。
     * @param key 对象存储键、配置键或业务幂等键。
     */
    public void reserveObject(Map<String, Object> run, String key) {
        jdbc.update(
                "INSERT INTO kb_remote_object_cleanup(id,document_id,kb_id,object_key,run_id,status,next_run_at) VALUES (?,?,?,?,?,'WAITING',clock_timestamp()+(?*interval '1 millisecond'))",
                UUID.randomUUID(),
                run.get("document_id"),
                run.get("kb_id"),
                key,
                run.get("id"),
                storage.apiTimeout().multipliedBy(2).plusSeconds(30).toMillis());
        jdbc.update(
                "UPDATE kb_refresh_run SET candidate_object_key=? WHERE id=?", key, run.get("id"));
    }

    /**
     * 为不再使用的远程源对象登记幂等清理任务。
     *
     * @param document 本次操作涉及的文档唯一标识。
     * @param kb 本次操作涉及的知识库唯一标识。
     * @param key 对象存储键、配置键或业务幂等键。
     * @param run 当前刷新运行记录或运行标识。
     */
    public void queueObject(UUID document, UUID kb, String key, UUID run) {
        if (key == null) return;
        jdbc.update(
                """
            INSERT INTO kb_remote_object_cleanup(id,document_id,kb_id,object_key,run_id,status,next_run_at)
            VALUES (?,?,?,?,?,'PENDING',clock_timestamp()+(?*interval '1 millisecond'))
            ON CONFLICT(object_key) DO UPDATE SET status=CASE WHEN kb_remote_object_cleanup.status='SUCCEEDED' THEN 'SUCCEEDED' ELSE 'PENDING' END,
            next_run_at=GREATEST(kb_remote_object_cleanup.next_run_at,EXCLUDED.next_run_at)
            """,
                UUID.randomUUID(),
                document,
                kb,
                key,
                run,
                storage.apiTimeout().multipliedBy(2).plusSeconds(30).toMillis());
    }

    /**
     * 结束刷新运行，释放租约并更新计划的最近状态和下次执行时间。
     *
     * @param run 当前刷新运行记录或运行标识。
     * @param status 当前业务操作或任务状态。
     * @param reason 本次跳过、失败或状态迁移的原因分类。
     */
    public void finish(Map<String, Object> run, String status, String reason) {
        OperationLog.committed(
                (UUID) run.get("kb_id"),
                (UUID) run.get("document_id"),
                (UUID) run.get("job_id"),
                null,
                "REFRESH",
                status);
        jdbc.update(
                """
            UPDATE kb_refresh_run SET status=?,reason=?,error_message=?,completed_at=clock_timestamp(),
            duration_ms=GREATEST(0,(EXTRACT(EPOCH FROM(clock_timestamp()-COALESCE(started_at,created_at)))*1000)::bigint),
            lease_owner=NULL,lease_until=NULL,next_run_at=NULL WHERE id=?
            """,
                status,
                reason,
                "FAILED".equals(status)
                        ? "Refresh failed; inspect reason and ingestion stages. Source and credentials omitted."
                        : null,
                run.get("id"));
        // schedule：当前文档的刷新计划记录。
        var schedule =
                jdbc.queryForMap(
                        "SELECT s.*,d.enabled AS document_enabled,d.deleted_at FROM kb_refresh_schedule s JOIN kb_document d ON d.id=s.document_id WHERE s.document_id=? FOR UPDATE",
                        run.get("document_id"));
        // next：下一次触发时间或下一跳远程地址。
        OffsetDateTime next =
                Boolean.TRUE.equals(schedule.get("enabled"))
                                && Boolean.TRUE.equals(schedule.get("document_enabled"))
                                && schedule.get("deleted_at") == null
                        ? next(
                                (String) schedule.get("cron_expression"),
                                (String) schedule.get("timezone"),
                                now())
                        : null;
        jdbc.update(
                """
            UPDATE kb_refresh_schedule SET last_status=?,last_reason=?,last_run_id=?,next_run_at=?,
            consecutive_failures=CASE WHEN ?='FAILED' THEN consecutive_failures+1 ELSE 0 END,
            lease_owner=NULL,lease_until=NULL,claim_token=claim_token+1,updated_at=clock_timestamp() WHERE document_id=?
            """,
                status,
                reason,
                run.get("id"),
                next,
                status,
                run.get("document_id"));
        if (!"SUCCESS".equals(status))
            queueObject(
                    (UUID) run.get("document_id"),
                    (UUID) run.get("kb_id"),
                    (String) run.get("candidate_object_key"),
                    (UUID) run.get("id"));
    }

    /**
     * 禁用文档刷新计划，使活动租约失效并收敛未完成运行。
     *
     * @param document 本次操作涉及的文档唯一标识。
     */
    public void stopDocument(UUID document) {
        // schedules：当前查询命中的刷新计划记录集合。
        var schedules =
                jdbc.queryForList(
                        "SELECT * FROM kb_refresh_schedule WHERE document_id=? FOR UPDATE",
                        document);
        if (schedules.isEmpty()) return;
        jdbc.update(
                "UPDATE kb_refresh_schedule SET enabled=FALSE,next_run_at=NULL,version=version+1,claim_token=claim_token+1,lease_owner=NULL,lease_until=NULL WHERE document_id=?",
                document);
        // run：当前刷新运行记录或运行标识。
        for (var run :
                jdbc.queryForList(
                        "SELECT * FROM kb_refresh_run WHERE document_id=? AND status IN ('PENDING','RUNNING','WAITING_INGESTION') FOR UPDATE",
                        document)) finish(run, "SKIPPED", "DOCUMENT_DISABLED_OR_DELETED");
    }

    /**
     * 读取数据库当前时间，使租约判断采用同一时间基准。
     *
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public OffsetDateTime now() {
        return jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class);
    }

    /**
     * 按指定时区计算 Cron 表达式在基准时刻之后的下一次触发。
     *
     * @param cron 刷新计划的 Cron 表达式。
     * @param timezone 解释刷新 Cron 表达式所使用的明确时区。
     * @param after 维护操作执行后的状态摘要或下一次触发的时间基准。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public static OffsetDateTime next(String cron, String timezone, OffsetDateTime after) {
        try {
            // result：当前步骤产生的处理结果。
            var result =
                    CronExpression.parse(cron).next(after.atZoneSameInstant(ZoneId.of(timezone)));
            if (result == null) throw new IllegalArgumentException();
            return result.toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC);
        } catch (RuntimeException error) {
            // error：本次处理捕获的异常，用于归类失败原因。
            throw new KnowledgeBaseException(
                    400,
                    "KB_INVALID_SCHEDULE",
                    "Provide a valid Spring six-field cron with a future occurrence and IANA timezone");
        }
    }
}
