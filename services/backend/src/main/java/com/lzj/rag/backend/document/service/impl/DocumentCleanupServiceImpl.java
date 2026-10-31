package com.lzj.rag.backend.document.service.impl;

import com.lzj.rag.backend.chunk.config.MaintenanceProperties;
import com.lzj.rag.backend.chunk.service.ChunkMaintenanceService;
import com.lzj.rag.backend.document.dao.mapper.DocumentMapper;
import com.lzj.rag.backend.document.service.DocumentCleanupService;
import com.lzj.rag.backend.document.service.DocumentService;
import com.lzj.rag.backend.document.service.bo.DocumentCleanupBO;
import com.lzj.rag.backend.document.storage.DocumentStorage;
import com.lzj.rag.backend.infra.storage.config.StorageProperties;
import com.lzj.rag.backend.ingestion.config.IngestionWorkerProperties;
import com.lzj.rag.backend.knowledgebase.exception.KnowledgeBaseException;
import com.lzj.rag.backend.knowledgebase.service.internal.KnowledgeBaseAccess;
import com.lzj.rag.backend.refresh.state.RefreshState;
import com.lzj.rag.backend.security.model.KnowledgeBasePrincipal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.*;
import java.util.*;

/** 先将文档标记删除，再通过持久化清理任务回收代次、切片和源对象。 */
@Service
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class DocumentCleanupServiceImpl implements DocumentCleanupService {

    /** 执行数据库查询及受条件保护的业务状态更新。 */
    private final JdbcTemplate jdbc;

    /** 供内部流程校验知识库归属和取得行锁的访问契约。 */
    private final KnowledgeBaseAccess bases;

    /** 负责文档归属、上传和配置维护的业务服务。 */
    private final DocumentService documents;

    /** 文档实体数据访问接口。 */
    private final DocumentMapper mapper;

    /** 负责人工切片维护及索引一致性的业务服务。 */
    private final ChunkMaintenanceService maintenance;

    /** 支持流式读写及幂等删除的源对象存储组件。 */
    private final DocumentStorage storage;

    /** 人工维护及后台清理的容量、租约和重试限制。 */
    private final MaintenanceProperties config;

    /** 入库工作线程、租约、重试退避和失效代次清理的配置。 */
    private final IngestionWorkerProperties worker;

    /** 用于短时状态变更的事务模板。 */
    private final TransactionTemplate tx;

    /** 当前服务实例的后台任务领取标识。 */
    private final String instance = "document-gc-" + UUID.randomUUID();

    /** 负责刷新凭据、源对象切换及终态维护的组件。 */
    private final RefreshState refresh;

    /** 不包含源内容或供应商详情的对外错误说明。 */
    private static final String SAFE_ERROR =
            "Cleanup incomplete; repair storage/database access and retry. Source and credentials are omitted.";

    /**
     * 初始化当前组件，保存业务依赖及本次使用的配置。
     *
     * @param jdbc 执行数据库查询及受条件保护的业务状态更新。
     * @param bases 供内部流程校验知识库归属和取得行锁的访问契约。
     * @param documents 负责文档归属、上传和配置维护的业务服务。
     * @param mapper 文档实体数据访问接口。
     * @param maintenance 负责人工切片维护及索引一致性的业务服务。
     * @param storage 支持流式读写及幂等删除的源对象存储组件。
     * @param config 人工维护及后台清理的容量、租约和重试限制。
     * @param worker 入库工作线程、租约、重试退避和失效代次清理的配置。
     * @param storageConfig 对象存储连接、文件容量、临时目录及上传补偿配置。
     * @param transactions 创建后台短事务及只读事务的事务管理器。
     * @param refresh 负责刷新凭据、源对象切换及终态维护的组件。
     */
    public DocumentCleanupServiceImpl(
            JdbcTemplate jdbc,
            KnowledgeBaseAccess bases,
            DocumentService documents,
            DocumentMapper mapper,
            ChunkMaintenanceService maintenance,
            DocumentStorage storage,
            MaintenanceProperties config,
            IngestionWorkerProperties worker,
            StorageProperties storageConfig,
            PlatformTransactionManager transactions,
            RefreshState refresh) {
        this.jdbc = jdbc;
        this.bases = bases;
        this.documents = documents;
        this.mapper = mapper;
        this.maintenance = maintenance;
        this.storage = storage;
        this.config = config;
        this.worker = worker;
        tx = new TransactionTemplate(transactions);
        tx.setTimeout(30);
        this.refresh = refresh;
        if (config.cleanupLease()
                        .compareTo(storageConfig.apiTimeout().multipliedBy(2).plusSeconds(10))
                < 0)
            throw new IllegalArgumentException(
                    "Document cleanup lease must exceed two bounded object API timeouts by at least 10s");
    }

    /**
     * 锁定并校验文档版本，写入删除标记和持久化清理任务。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param id 当前业务对象的唯一标识。
     * @param version 当前记录版本或本次请求固定的版本标记。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public DocumentCleanupBO delete(KnowledgeBasePrincipal actor, UUID id, long version) {
        return tx.execute(
                status -> {
                    // status：本次短事务的执行状态，由事务模板传入。
                    // rows：数据库结果或当前表格数据行集合。
                    var rows =
                            jdbc.queryForList(
                                    "SELECT kb_id,deleted_at FROM kb_document WHERE id=? AND tenant_id=? AND owner_id=?",
                                    id,
                                    actor.tenantId(),
                                    actor.userId());
                    if (rows.isEmpty()) throw notFound();
                    // row：当前数据库记录或表格数据行。
                    var row = rows.get(0);
                    if (row.get("deleted_at") != null) return ownedByDocument(actor, id);
                    bases.lockOwned(actor, (UUID) row.get("kb_id"));
                    jdbc.queryForObject(
                            "SELECT id FROM kb_document WHERE id=? FOR UPDATE", UUID.class, id);
                    // doc：当前操作涉及的文档。
                    var doc = mapper.selectById(id);
                    if (doc.getDeletedAt() != null) return ownedByDocument(actor, id);
                    if (doc.getVersion() != version)
                        throw new KnowledgeBaseException(
                                409,
                                "KB_VERSION_CONFLICT",
                                "Reload the current document version and retry");
                    // before：维护操作执行前的状态摘要。
                    var before = maintenance.summary(doc);
                    maintenance.cancelActive(actor, id);
                    refresh.stopDocument(id);
                    doc.setEnabled(false);
                    doc.setDeletedAt(OffsetDateTime.now(ZoneOffset.UTC));
                    doc.setUpdatedAt(doc.getDeletedAt());
                    doc.setUpdatedBy(actor.userId());
                    doc.setVersion(doc.getVersion() + 1);
                    doc.setChunkCount(0L);
                    // updateById 会跳过空值，因此需要在同一事务中显式清空当前代次。
                    mapper.updateById(doc);
                    jdbc.update("UPDATE kb_document SET active_generation=NULL WHERE id=?", id);
                    // operation：持久化上传操作、操作标识或审计动作名称。
                    UUID operation = UUID.randomUUID();
                    jdbc.update(
                            """
                INSERT INTO kb_document_cleanup(id,document_id,kb_id,tenant_id,owner_id,object_key,max_attempts)
                VALUES (?,?,?,?,?,?,?)
                """,
                            operation,
                            id,
                            doc.getKbId(),
                            actor.tenantId(),
                            actor.userId(),
                            doc.getObjectKey(),
                            worker.cleanupMaxAttempts());
                    maintenance.audit(
                            actor,
                            doc,
                            "DOCUMENT_DELETE",
                            List.of(),
                            before,
                            maintenance.summary(doc));
                    return get(actor, operation);
                });
    }

    /**
     * 读取当前用户可访问的文档清理任务详情。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param id 当前业务对象的唯一标识。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public DocumentCleanupBO get(KnowledgeBasePrincipal actor, UUID id) {
        // rs：数据库查询的当前结果行。
        // n：当前结果行的顺序编号，由 JDBC 行映射器传入。
        // rows：数据库结果或当前表格数据行集合。
        var rows =
                jdbc.query(
                        "SELECT * FROM kb_document_cleanup WHERE id=? AND tenant_id=? AND owner_id=?",
                        (rs, n) -> view(rs),
                        id,
                        actor.tenantId(),
                        actor.userId());
        if (rows.isEmpty()) throw notFound();
        return rows.get(0);
    }

    /**
     * 根据文档标识查询其清理任务，并校验任务归属。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param id 当前业务对象的唯一标识。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private DocumentCleanupBO ownedByDocument(KnowledgeBasePrincipal actor, UUID id) {
        // ids：本次操作涉及的业务对象标识集合。
        var ids =
                jdbc.queryForList(
                        "SELECT id FROM kb_document_cleanup WHERE document_id=? AND tenant_id=? AND owner_id=?",
                        UUID.class,
                        id,
                        actor.tenantId(),
                        actor.userId());
        if (ids.isEmpty()) throw notFound();
        return get(actor, ids.get(0));
    }

    /**
     * 重置可重试的清理任务，保留有限重试策略。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param id 当前业务对象的唯一标识。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public DocumentCleanupBO retry(KnowledgeBasePrincipal actor, UUID id) {
        get(actor, id);
        return tx.execute(
                status -> {
                    // status：本次短事务的执行状态，由事务模板传入。
                    lock(id);
                    // row：当前数据库记录或表格数据行。
                    var row =
                            jdbc.queryForMap(
                                    "SELECT *,lease_until>clock_timestamp() AS valid FROM kb_document_cleanup WHERE id=? FOR UPDATE",
                                    id);
                    if ("RUNNING".equals(row.get("status")))
                        throw new KnowledgeBaseException(
                                409,
                                "KB_CLEANUP_ACTIVE",
                                "Cleanup is running or awaits expired-lease recovery");
                    if ("FAILED".equals(row.get("status")))
                        jdbc.update(
                                """
                    UPDATE kb_document_cleanup SET status='PENDING',attempts=0,next_run_at=clock_timestamp(),completed_at=NULL,
                    error_code=NULL,error_message=NULL,claim_token=claim_token+1 WHERE id=?
                    """,
                                id);
                    return get(actor, id);
                });
    }

    /** 定时执行一批到期清理任务，单项失败不阻塞后续扫描。 */
    @Scheduled(
            fixedDelayString = "${kb.worker.document-cleanup-poll-delay:10000}",
            initialDelayString = "${kb.worker.initial-delay:1000}")
    public void scan() {
        if (!worker.enabled()) return;
        try {
            runOnce();
        } catch (RuntimeException error) {
            // error：本次处理捕获的异常，用于归类失败原因。
            org.slf4j.LoggerFactory.getLogger(getClass())
                    .warn("Document cleanup paused; durable intent remains recoverable");
        }
    }

    /**
     * 领取有效租约，删除文档关联数据及源对象，再按凭据提交清理结果。
     *
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public boolean runOnce() {
        // claim：当前后台任务的租约凭据或领取记录。
        var claim =
                tx.execute(
                        status -> {
                            // status：本次短事务的执行状态，由事务模板传入。
                            // candidates：本次待领取的任务、可选解析策略或召回候选集合。
                            var candidates =
                                    jdbc.queryForList(
                                            """
                SELECT id,kb_id,document_id FROM kb_document_cleanup WHERE
                (status='PENDING' AND next_run_at<=clock_timestamp()) OR (status='RUNNING' AND lease_until<=clock_timestamp())
                ORDER BY COALESCE(next_run_at,lease_until),id LIMIT 32
                """);
                            // c：当前待领取或待收敛的持久化任务记录。
                            for (var c : candidates) {
                                if (jdbc.queryForList(
                                                "SELECT id FROM kb_knowledge_base WHERE id=? FOR UPDATE SKIP LOCKED",
                                                UUID.class,
                                                c.get("kb_id"))
                                        .isEmpty()) continue;
                                if (jdbc.queryForList(
                                                "SELECT id FROM kb_document WHERE id=? FOR UPDATE SKIP LOCKED",
                                                UUID.class,
                                                c.get("document_id"))
                                        .isEmpty()) continue;
                                // rows：数据库结果或当前表格数据行集合。
                                var rows =
                                        jdbc.queryForList(
                                                """
                    SELECT * FROM kb_document_cleanup WHERE id=? AND
                    ((status='PENDING' AND next_run_at<=clock_timestamp()) OR (status='RUNNING' AND lease_until<=clock_timestamp())) FOR UPDATE SKIP LOCKED
                    """,
                                                c.get("id"));
                                if (rows.isEmpty()) continue;
                                // row：当前数据库记录或表格数据行。
                                var row = rows.get(0);
                                if (((Number) row.get("attempts")).intValue()
                                        >= ((Number) row.get("max_attempts")).intValue()) {
                                    jdbc.update(
                                            "UPDATE kb_document_cleanup SET status='FAILED',lease_owner=NULL,lease_until=NULL,next_run_at=NULL,completed_at=clock_timestamp(),claim_token=claim_token+1,error_code='KB_CLEANUP_EXPIRED',error_message=? WHERE id=?",
                                            SAFE_ERROR,
                                            c.get("id"));
                                    continue;
                                }
                                return jdbc.queryForMap(
                                        """
                    UPDATE kb_document_cleanup SET status='RUNNING',attempts=attempts+1,claim_token=claim_token+1,lease_owner=?,
                    lease_until=clock_timestamp()+(?*interval '1 millisecond'),next_run_at=NULL WHERE id=? RETURNING *
                    """,
                                        instance,
                                        config.cleanupLease().toMillis(),
                                        c.get("id"));
                            }
                            return null;
                        });
        if (claim == null) return false;
        try {
            // ready：关键词索引结构是否已经完成可用性校验。
            boolean ready =
                    Boolean.TRUE.equals(
                            tx.execute(
                                    status -> {
                                        // status：本次短事务的执行状态，由事务模板传入。
                                        if (!ownedClaim(claim)) return false;
                                        if ("DATABASE".equals(claim.get("stage"))) {
                                            // doc：本次操作涉及的文档唯一标识。
                                            UUID doc = (UUID) claim.get("document_id");
                                            if (!Boolean.TRUE.equals(
                                                    jdbc.queryForObject(
                                                            "SELECT deleted_at IS NOT NULL FROM kb_document WHERE id=?",
                                                            Boolean.class,
                                                            doc)))
                                                throw new IllegalStateException(
                                                        "Cleanup requires document tombstone");
                                            // 向量记录由当前数据库的外键级联删除。
                                            jdbc.update(
                                                    "DELETE FROM kb_chunk WHERE document_id=?",
                                                    doc);
                                            jdbc.update(
                                                    "DELETE FROM kb_generation WHERE document_id=?",
                                                    doc);
                                            jdbc.update(
                                                    "UPDATE kb_generation_cleanup SET status='SUCCEEDED',completed_at=clock_timestamp(),error_code=NULL WHERE document_id=?",
                                                    doc);
                                            jdbc.update(
                                                    "UPDATE kb_document_cleanup SET stage='OBJECT' WHERE id=?",
                                                    claim.get("id"));
                                        }
                                        return true;
                                    }));
            if (!ready) return true;
            // 对象删除在数据库事务外执行，且该稳定对象键不会被后续上传复用。
            storage.delete((String) claim.get("object_key"));
            tx.executeWithoutResult(
                    status -> {
                        // status：本次短事务的执行状态，由事务模板传入。
                        if (!ownedClaim(claim)) return;
                        jdbc.update(
                                "UPDATE kb_document_cleanup SET status='SUCCEEDED',completed_at=clock_timestamp(),lease_owner=NULL,lease_until=NULL,next_run_at=NULL,error_code=NULL,error_message=NULL WHERE id=?",
                                claim.get("id"));
                        jdbc.update(
                                "UPDATE kb_upload_operation SET status='CLEANED',updated_at=clock_timestamp() WHERE id=? AND status='COMPLETED'",
                                claim.get("document_id"));
                    });
        } catch (RuntimeException error) {
            // error：本次处理捕获的异常，用于归类失败原因。
            // 数据库不可用时保留已持久化的租约，待其到期后由其他实例恢复。
            tx.executeWithoutResult(
                    status -> {
                        // status：本次短事务的执行状态，由事务模板传入。
                        if (!ownedClaim(claim)) return;
                        // exhausted：重试次数是否已经达到上限。
                        boolean exhausted =
                                ((Number) claim.get("attempts")).intValue()
                                        >= ((Number) claim.get("max_attempts")).intValue();
                        // kb：本次捕获或匹配的异常，用于保留业务分类及失败原因。
                        jdbc.update(
                                """
                    UPDATE kb_document_cleanup SET status=?,lease_owner=NULL,lease_until=NULL,claim_token=claim_token+1,
                    next_run_at=CASE WHEN ? THEN NULL ELSE clock_timestamp()+(?*interval '1 millisecond') END,
                    completed_at=CASE WHEN ? THEN clock_timestamp() ELSE NULL END,error_code=?,error_message=? WHERE id=?
                    """,
                                exhausted ? "FAILED" : "PENDING",
                                exhausted,
                                worker.backoffMillis(((Number) claim.get("attempts")).intValue()),
                                exhausted,
                                error instanceof KnowledgeBaseException kb
                                        ? kb.code()
                                        : "KB_CLEANUP_FAILED",
                                SAFE_ERROR,
                                claim.get("id"));
                    });
        }
        return true;
    }

    /**
     * 按知识库和文档顺序取得清理所需业务锁。
     *
     * @param id 当前业务对象的唯一标识。
     */
    private void lock(UUID id) {
        // row：当前数据库记录或表格数据行。
        var row =
                jdbc.queryForMap(
                        "SELECT kb_id,document_id FROM kb_document_cleanup WHERE id=?", id);
        jdbc.queryForObject(
                "SELECT id FROM kb_knowledge_base WHERE id=? FOR UPDATE",
                UUID.class,
                row.get("kb_id"));
        jdbc.queryForObject(
                "SELECT id FROM kb_document WHERE id=? FOR UPDATE",
                UUID.class,
                row.get("document_id"));
    }

    /**
     * 检查清理记录仍由本次租约持有且未过期。
     *
     * @param claim 当前后台任务的租约凭据或领取记录。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private boolean ownedClaim(Map<String, Object> claim) {
        lock((UUID) claim.get("id"));
        return !jdbc.queryForList(
                        "SELECT id FROM kb_document_cleanup WHERE id=? AND status='RUNNING' AND claim_token=? AND lease_owner=? AND lease_until>clock_timestamp() FOR UPDATE",
                        claim.get("id"),
                        claim.get("claim_token"),
                        instance)
                .isEmpty();
    }

    /**
     * 将清理任务数据库记录转换为查询响应。
     *
     * @param rs 数据库查询的当前结果行。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private DocumentCleanupBO view(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new DocumentCleanupBO(
                rs.getObject("id", UUID.class),
                rs.getObject("document_id", UUID.class),
                rs.getString("status"),
                rs.getString("stage"),
                rs.getInt("attempts"),
                rs.getInt("max_attempts"),
                rs.getObject("next_run_at", OffsetDateTime.class),
                rs.getObject("lease_until", OffsetDateTime.class),
                rs.getString("error_code"),
                rs.getString("error_message"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("completed_at", OffsetDateTime.class));
    }

    /**
     * 创建当前权限范围内未找到资源的异常。
     *
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private static KnowledgeBaseException notFound() {
        return new KnowledgeBaseException(
                404, "KB_DOCUMENT_NOT_FOUND", "Document or cleanup operation not found");
    }
}
