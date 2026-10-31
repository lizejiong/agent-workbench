package com.lzj.rag.backend.search.service.impl;

import com.lzj.rag.backend.document.service.internal.DocumentAccess;
import com.lzj.rag.backend.knowledgebase.exception.KnowledgeBaseException;
import com.lzj.rag.backend.knowledgebase.service.internal.KnowledgeBaseAccess;
import com.lzj.rag.backend.search.config.KeywordProperties;
import com.lzj.rag.backend.search.repository.keyword.KeywordIndex;
import com.lzj.rag.backend.search.repository.keyword.dto.KeywordChunkDTO;
import com.lzj.rag.backend.search.service.KeywordSyncService;
import com.lzj.rag.backend.search.service.KnowledgeSearchService;
import com.lzj.rag.backend.search.service.bo.KeywordSyncBO;
import com.lzj.rag.backend.search.service.bo.KeywordSyncClaimBO;
import com.lzj.rag.backend.security.model.KnowledgeBasePrincipal;
import com.lzj.rag.framework.convention.page.PageResponse;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** 消费数据库同步任务，通过版本校验和租约保护关键词索引发布。 */
@Service
public class KeywordSyncServiceImpl implements KeywordSyncService {

    /** 执行数据库查询及受条件保护的业务状态更新。 */
    private final JdbcTemplate jdbc;

    /** 提供关键词索引写入、查询和历史版本删除的组件。 */
    private final KeywordIndex index;

    /** 关键词检索开关、索引身份、分析器及同步工作线程配置。 */
    private final KeywordProperties config;

    /** 供内部流程校验知识库归属和取得行锁的访问契约。 */
    private final KnowledgeBaseAccess bases;

    /** 供内部流程校验文档归属和取得行锁的访问契约。 */
    private final DocumentAccess documents;

    /** 校验检索范围并执行向量召回的业务服务。 */
    private final KnowledgeSearchService sources;

    /** 用于短时状态变更的事务模板。 */
    private final TransactionTemplate tx;

    /** 本实例持有的运行中任务及其执行凭据集合。 */
    private final Map<String, KeywordSyncClaimBO> running = new ConcurrentHashMap<>();

    /** 当前服务实例的后台任务领取标识。 */
    private final String instance = "keyword-" + UUID.randomUUID();

    /**
     * 初始化当前组件，保存业务依赖及本次使用的配置。
     *
     * @param jdbc 执行数据库查询及受条件保护的业务状态更新。
     * @param index 提供关键词索引写入、查询和历史版本删除的组件。
     * @param config 关键词检索开关、索引身份、分析器及同步工作线程配置。
     * @param bases 供内部流程校验知识库归属和取得行锁的访问契约。
     * @param documents 供内部流程校验文档归属和取得行锁的访问契约。
     * @param sources 校验检索范围并执行向量召回的业务服务。
     * @param manager 创建后台短事务及只读事务的事务管理器。
     */
    public KeywordSyncServiceImpl(
            JdbcTemplate jdbc,
            KeywordIndex index,
            KeywordProperties config,
            KnowledgeBaseAccess bases,
            DocumentAccess documents,
            KnowledgeSearchService sources,
            PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.index = index;
        this.config = config;
        this.bases = bases;
        this.documents = documents;
        this.sources = sources;
        tx = new TransactionTemplate(manager);
        tx.setTimeout(10);
    }

    /**
     * 读取当前用户知识库的关键词同步状态。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param doc 本次操作涉及的文档唯一标识。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @Transactional(readOnly = true)
    public KeywordSyncBO get(KnowledgeBasePrincipal actor, UUID doc) {
        documents.requireOwned(actor, doc);
        return view(doc);
    }

    /**
     * 在文档归属范围内分页查询关键词同步操作。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param kb 本次操作涉及的知识库唯一标识。
     * @param page 当前源页面编号或分页页码。
     * @param size 分页容量或当前数据的字节数。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResponse<KeywordSyncBO> list(
            KnowledgeBasePrincipal actor, UUID kb, int page, int size) {
        bases.requireOwned(actor, kb);
        // count：当前累计数量或本次流读取的有效字节数。
        long count =
                jdbc.queryForObject(
                        "SELECT count(*) FROM kb_keyword_sync WHERE kb_id=? AND tenant_id=? AND owner_id=?",
                        Long.class,
                        kb,
                        actor.tenantId(),
                        actor.userId());
        // ids：本次操作涉及的业务对象标识集合。
        var ids =
                jdbc.queryForList(
                        "SELECT document_id FROM kb_keyword_sync WHERE kb_id=? AND tenant_id=? AND owner_id=? ORDER BY updated_at DESC,document_id LIMIT ? OFFSET ?",
                        UUID.class,
                        kb,
                        actor.tenantId(),
                        actor.userId(),
                        size,
                        ((long) page - 1) * size);
        return new PageResponse<>(page, size, count, ids.stream().map(this::view).toList());
    }

    /**
     * 校验归属与当前版本后重新安排失败的同步任务。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param kb 本次操作涉及的知识库唯一标识。
     * @param doc 本次操作涉及的文档唯一标识。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @Transactional(timeout = 10)
    public KeywordSyncBO retry(KnowledgeBasePrincipal actor, UUID kb, UUID doc) {
        bases.lockOwned(actor, kb);
        // docs：当前范围内的文档记录集合。
        var docs =
                jdbc.queryForList(
                        "SELECT id FROM kb_document WHERE id=? AND kb_id=? AND tenant_id=? AND owner_id=? FOR UPDATE",
                        UUID.class,
                        doc,
                        kb,
                        actor.tenantId(),
                        actor.userId());
        if (docs.isEmpty())
            throw new KnowledgeBaseException(404, "KB_DOCUMENT_NOT_FOUND", "Document not found");
        // row：当前数据库记录或表格数据行。
        var row =
                jdbc.queryForMap(
                        "SELECT * FROM kb_keyword_sync WHERE document_id=? FOR UPDATE", doc);
        if ("RUNNING".equals(row.get("status")))
            throw new KnowledgeBaseException(
                    409,
                    "KB_KEYWORD_SYNC_ACTIVE",
                    "Wait for sync lease recovery before rebuilding");
        jdbc.update(
                "UPDATE kb_keyword_sync SET revision=revision+1,status='PENDING',attempts=0,next_run_at=clock_timestamp(),claim_token=claim_token+1,error_code=NULL WHERE document_id=?",
                doc);
        jdbc.update(
                "UPDATE kb_keyword_cleanup SET status='PENDING',attempts=0,next_run_at=clock_timestamp(),error_code=NULL WHERE document_id=? AND status='FAILED'",
                doc);
        return view(doc);
    }

    /**
     * 将指定同步操作转换为公开查询响应。
     *
     * @param doc 本次操作涉及的文档唯一标识。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private KeywordSyncBO view(UUID doc) {
        // rs：数据库查询的当前结果行。
        // n：当前结果行的顺序编号，由 JDBC 行映射器传入。
        return jdbc.queryForObject(
                "SELECT s.*,c.status AS cleanup_status,c.error_code AS cleanup_error FROM kb_keyword_sync s LEFT JOIN kb_keyword_cleanup c USING(document_id) WHERE s.document_id=?",
                (rs, n) ->
                        new KeywordSyncBO(
                                doc,
                                rs.getLong("revision"),
                                rs.getLong("synced_revision"),
                                rs.getString("status"),
                                rs.getInt("attempts"),
                                rs.getObject("next_run_at", OffsetDateTime.class),
                                rs.getObject("lease_until", OffsetDateTime.class),
                                rs.getString("error_code"),
                                rs.getObject("synced_at", OffsetDateTime.class),
                                rs.getString("cleanup_status"),
                                rs.getString("cleanup_error")),
                doc);
    }

    /**
     * 按业务锁顺序领取同步任务，并处理过期租约与失效输入版本。
     *
     * @param cleanup 是否执行历史索引清理，或当前对象清理任务记录。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public KeywordSyncClaimBO claimNext(boolean cleanup) {
        if (!config.enabled()) return null;
        return tx.execute(
                status -> {
                    // status：本次短事务的执行状态，由事务模板传入。
                    // table：当前表格名称或渲染得到的表格文本。
                    String table = cleanup ? "kb_keyword_cleanup" : "kb_keyword_sync";
                    if (!cleanup)
                        jdbc.update(
                                "UPDATE kb_keyword_sync SET revision=revision+1,status='PENDING',attempts=0,next_run_at=clock_timestamp() WHERE status='SUCCEEDED' AND indexed_target<>?",
                                config.target());
                    // due：匹配到期可领取任务的 SQL 条件。
                    String due =
                            cleanup
                                    ? "(status IN ('PENDING','SUCCEEDED') AND next_run_at<=clock_timestamp())"
                                    : "(status='PENDING' AND next_run_at<=clock_timestamp())";
                    // row：当前数据库记录或表格数据行。
                    for (var row :
                            jdbc.queryForList(
                                    "SELECT document_id,kb_id FROM "
                                            + table
                                            + " WHERE "
                                            + due
                                            + " OR (status='RUNNING' AND lease_until<=clock_timestamp()) ORDER BY next_run_at,document_id LIMIT 32")) {
                        if (!locks((UUID) row.get("kb_id"), (UUID) row.get("document_id"), true))
                            continue;
                        // candidates：本次待领取的任务、可选解析策略或召回候选集合。
                        var candidates =
                                jdbc.queryForList(
                                        "SELECT * FROM "
                                                + table
                                                + " WHERE document_id=? AND ("
                                                + due
                                                + " OR (status='RUNNING' AND lease_until<=clock_timestamp())) FOR UPDATE SKIP LOCKED",
                                        row.get("document_id"));
                        if (candidates.isEmpty()) continue;
                        // item：当前处理的集合元素。
                        var item = candidates.get(0);
                        // attempts：已发生的执行尝试次数。
                        int attempts = ((Number) item.get("attempts")).intValue();
                        if (!cleanup
                                && "RUNNING".equals(item.get("status"))
                                && !Objects.equals(
                                        item.get("revision"), item.get("claimed_revision"))) {
                            jdbc.update(
                                    "UPDATE kb_keyword_sync SET status='PENDING',attempts=0,claim_token=claim_token+1,lease_owner=NULL,lease_until=NULL,next_run_at=clock_timestamp() WHERE document_id=?",
                                    row.get("document_id"));
                            continue;
                        }
                        if (attempts >= config.maxAttempts()
                                && !"SUCCEEDED".equals(item.get("status"))) {
                            jdbc.update(
                                    "UPDATE "
                                            + table
                                            + " SET status='FAILED',error_code='KB_KEYWORD_ATTEMPTS_EXHAUSTED',lease_owner=NULL,lease_until=NULL WHERE document_id=?",
                                    row.get("document_id"));
                            continue;
                        }
                        // claimed：成功领取后携带有效执行凭据的任务记录。
                        var claimed =
                                jdbc.queryForMap(
                                        "UPDATE "
                                                + table
                                                + " SET status='RUNNING',attempts=CASE WHEN status='SUCCEEDED' THEN 1 ELSE attempts+1 END,claim_token=claim_token+1,lease_owner=?,lease_until=clock_timestamp()+(?*interval '1 millisecond'),attempt_deadline=clock_timestamp()+interval '2 minutes',"
                                                + (cleanup
                                                        ? "claimed_watermark=watermark"
                                                        : "claimed_revision=revision")
                                                + " WHERE document_id=? RETURNING *",
                                        instance,
                                        config.lease().toMillis(),
                                        row.get("document_id"));
                        // deadline：本阶段或本次请求必须完成的截止时间。
                        Object deadline = claimed.get("attempt_deadline");
                        // offset：分页偏移量或数据库时间的带偏移表示。
                        // instant：从数据库时间值转换得到的绝对时刻。
                        Instant instant =
                                deadline instanceof OffsetDateTime offset
                                        ? offset.toInstant()
                                        : ((java.sql.Timestamp) deadline).toInstant();
                        return new KeywordSyncClaimBO(
                                (UUID) row.get("document_id"),
                                (UUID) row.get("kb_id"),
                                ((Number) claimed.get("claim_token")).longValue(),
                                ((Number)
                                                claimed.get(
                                                        cleanup
                                                                ? "claimed_watermark"
                                                                : "claimed_revision"))
                                        .longValue(),
                                cleanup,
                                instant);
                    }
                    return null;
                });
    }

    /**
     * 领取并执行一次关键词同步任务。
     *
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public boolean runOnce() {
        // claim：当前后台任务的租约凭据或领取记录。
        var claim = claimNext(false);
        if (claim != null) execute(claim);
        // cleanup：是否执行历史索引清理，或当前对象清理任务记录。
        var cleanup = claimNext(true);
        if (cleanup != null) execute(cleanup);
        return claim != null || cleanup != null;
    }

    /**
     * 在租约保护下分批构建索引，复核版本后发布同步状态并回收旧版本。
     *
     * @param claim 当前后台任务的租约凭据或领取记录。
     */
    public void execute(KeywordSyncClaimBO claim) {
        // key：对象存储键、配置键或业务幂等键。
        String key = claim.cleanup() + "/" + claim.documentId();
        if (running.putIfAbsent(key, claim) != null) return;
        try {
            if (claim.cleanup())
                index.deleteBefore(claim.documentId(), claim.revision(), claim.deadline());
            else {
                // rows：数据库结果或当前表格数据行集合。
                var rows =
                        tx.execute(
                                status -> {
                                    // status：本次短事务的执行状态，由事务模板传入。
                                    locks(claim.kbId(), claim.documentId(), false);
                                    owned(claim);
                                    return jdbc.queryForList(
                                            """
                        SELECT c.*,d.name AS document_name,d.enabled AS document_enabled FROM kb_chunk c JOIN kb_document d ON d.id=c.document_id
                        JOIN kb_knowledge_base b ON b.id=d.kb_id
                        WHERE d.id=? AND d.active_generation=c.generation
                        AND d.deleted_at IS NULL AND b.deleted_at IS NULL ORDER BY c.chunk_index,c.id
                        """,
                                            claim.documentId());
                                });
                // chunks：本次处理的有序切片集合。
                var chunks = new ArrayList<KeywordChunkDTO>();
                // row：当前数据库记录或表格数据行。
                for (var row : rows)
                    chunks.add(
                            new KeywordChunkDTO(
                                    claim.documentId()
                                            + ":"
                                            + claim.revision()
                                            + ":"
                                            + row.get("id"),
                                    (UUID) row.get("id"),
                                    (UUID) row.get("kb_id"),
                                    claim.documentId(),
                                    (String) row.get("tenant_id"),
                                    (String) row.get("owner_id"),
                                    ((Number) row.get("generation")).longValue(),
                                    claim.revision(),
                                    claim.documentId() + ":" + claim.revision(),
                                    (String) row.get("content_hash"),
                                    (String) row.get("document_name"),
                                    ((Number) row.get("chunk_index")).intValue(),
                                    (String) row.get("content"),
                                    Boolean.TRUE.equals(row.get("enabled"))
                                            && Boolean.TRUE.equals(row.get("document_enabled")),
                                    Boolean.TRUE.equals(row.get("enabled")),
                                    Boolean.TRUE.equals(row.get("document_enabled")),
                                    sources.source(row.get("metadata_json").toString())));
                // 同步第二阶段：写入固定修订的切片，写入期间仍受当前租约约束。
                index.write(chunks, claim.deadline());
            }
            tx.executeWithoutResult(
                    status -> {
                        // status：本次短事务的执行状态，由事务模板传入。
                        locks(claim.kbId(), claim.documentId(), false);
                        // row：当前数据库记录或表格数据行。
                        var row = owned(claim);
                        if (claim.cleanup()) {
                            // changed：输入版本是否发生变化，或条件更新实际影响的行数。
                            boolean changed =
                                    ((Number) row.get("watermark")).longValue() != claim.revision();
                            jdbc.update(
                                    "UPDATE kb_keyword_cleanup SET status=?,attempts=0,next_run_at=clock_timestamp()+(?*interval '1 second'),lease_owner=NULL,lease_until=NULL,error_code=NULL WHERE document_id=?",
                                    changed ? "PENDING" : "SUCCEEDED",
                                    changed ? 0 : 3600,
                                    claim.documentId());
                        } else if (((Number) row.get("revision")).longValue() != claim.revision())
                            releaseChanged(claim);
                        else {
                            jdbc.update(
                                    "UPDATE kb_keyword_sync SET status='SUCCEEDED',synced_revision=?,indexed_target=?,synced_at=clock_timestamp(),next_run_at=NULL,lease_owner=NULL,lease_until=NULL,error_code=NULL WHERE document_id=?",
                                    claim.revision(),
                                    config.target(),
                                    claim.documentId());
                            jdbc.update(
                                    """
                        INSERT INTO kb_keyword_cleanup(document_id,kb_id,watermark,next_run_at) VALUES (?,?,?,clock_timestamp()+interval '40 seconds')
                        ON CONFLICT(document_id) DO UPDATE SET watermark=GREATEST(kb_keyword_cleanup.watermark,EXCLUDED.watermark),
                        status=CASE WHEN kb_keyword_cleanup.status='RUNNING' THEN 'RUNNING' ELSE 'PENDING' END,
                        attempts=CASE WHEN kb_keyword_cleanup.status='RUNNING' THEN kb_keyword_cleanup.attempts ELSE 0 END,
                        next_run_at=clock_timestamp()+interval '40 seconds'
                        """,
                                    claim.documentId(),
                                    claim.kbId(),
                                    claim.revision());
                        }
                    });
        } catch (RuntimeException error) {
            // error：本次处理捕获的异常，用于归类失败原因。
            if (Thread.currentThread().isInterrupted()) return;
            tx.executeWithoutResult(
                    status -> {
                        // status：本次短事务的执行状态，由事务模板传入。
                        locks(claim.kbId(), claim.documentId(), false);
                        // row：当前数据库记录或表格数据行。
                        Map<String, Object> row;
                        try {
                            row = owned(claim);
                        } catch (KnowledgeBaseException lost) {
                            // lost：当前尝试失去有效租约时产生的业务异常。
                            return;
                        }
                        if (!claim.cleanup()
                                && ((Number) row.get("revision")).longValue() != claim.revision()) {
                            releaseChanged(claim);
                            return;
                        }
                        // terminal：任务终态或是否已进入终态。
                        boolean terminal =
                                ((Number) row.get("attempts")).intValue() >= config.maxAttempts();
                        // kb：本次捕获或匹配的异常，用于保留业务分类及失败原因。
                        jdbc.update(
                                "UPDATE "
                                        + (claim.cleanup()
                                                ? "kb_keyword_cleanup"
                                                : "kb_keyword_sync")
                                        + " SET status=?,next_run_at=clock_timestamp()+(?*interval '1 second'),lease_owner=NULL,lease_until=NULL,error_code=? WHERE document_id=?",
                                terminal ? "FAILED" : "PENDING",
                                Math.min(
                                        60,
                                        2L
                                                << Math.min(
                                                        8,
                                                        ((Number) row.get("attempts")).intValue()
                                                                - 1)),
                                error instanceof KnowledgeBaseException kb
                                        ? kb.code()
                                        : "KB_KEYWORD_SYNC_FAILED",
                                claim.documentId());
                    });
        } finally {
            running.remove(key, claim);
        }
    }

    /** 为本实例仍有效的关键词同步任务续租。 */
    public void heartbeat() {
        // claim：当前后台任务的租约凭据或领取记录。
        for (var claim : running.values())
            tx.executeWithoutResult(
                    status -> {
                        // status：本次短事务的执行状态，由事务模板传入。
                        if (!locks(claim.kbId(), claim.documentId(), true)) return;
                        jdbc.update(
                                "UPDATE "
                                        + (claim.cleanup()
                                                ? "kb_keyword_cleanup"
                                                : "kb_keyword_sync")
                                        + " SET lease_until=LEAST(attempt_deadline,clock_timestamp()+(?*interval '1 millisecond')) WHERE document_id=? AND status='RUNNING' AND claim_token=? AND lease_owner=? AND lease_until>clock_timestamp() AND attempt_deadline>clock_timestamp()",
                                config.lease().toMillis(),
                                claim.documentId(),
                                claim.token(),
                                instance);
                    });
    }

    /**
     * 确认同步凭据仍持有未过期租约和当前有效输入版本。
     *
     * @param claim 当前后台任务的租约凭据或领取记录。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private Map<String, Object> owned(KeywordSyncClaimBO claim) {
        // rows：数据库结果或当前表格数据行集合。
        var rows =
                jdbc.queryForList(
                        "SELECT * FROM "
                                + (claim.cleanup() ? "kb_keyword_cleanup" : "kb_keyword_sync")
                                + " WHERE document_id=? AND status='RUNNING' AND claim_token=? AND lease_owner=? AND lease_until>clock_timestamp() AND attempt_deadline>clock_timestamp() FOR UPDATE",
                        claim.documentId(),
                        claim.token(),
                        instance);
        if (rows.isEmpty())
            throw new KnowledgeBaseException(
                    409, "KB_KEYWORD_CLAIM_LOST", "Keyword sync lease expired");
        return rows.get(0);
    }

    /**
     * 输入已变化时释放当前同步任务，避免将旧结果标记为可见。
     *
     * @param claim 当前后台任务的租约凭据或领取记录。
     */
    private void releaseChanged(KeywordSyncClaimBO claim) {
        jdbc.update(
                "UPDATE kb_keyword_sync SET status='PENDING',attempts=0,next_run_at=clock_timestamp(),claim_token=claim_token+1,lease_owner=NULL,lease_until=NULL WHERE document_id=?",
                claim.documentId());
    }

    /**
     * 按知识库、文档顺序获取业务锁，可选择非阻塞领取。
     *
     * @param kb 本次操作涉及的知识库唯一标识。
     * @param document 本次操作涉及的文档唯一标识。
     * @param skip 当前输入是否应按业务规则跳过。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private boolean locks(UUID kb, UUID document, boolean skip) {
        // suffix：输出片段的后缀内容。
        String suffix = " FOR UPDATE" + (skip ? " SKIP LOCKED" : "");
        return !jdbc.queryForList(
                                "SELECT id FROM kb_knowledge_base WHERE id=?" + suffix,
                                UUID.class,
                                kb)
                        .isEmpty()
                && !jdbc.queryForList(
                                "SELECT id FROM kb_document WHERE id=?" + suffix,
                                UUID.class,
                                document)
                        .isEmpty();
    }
}
