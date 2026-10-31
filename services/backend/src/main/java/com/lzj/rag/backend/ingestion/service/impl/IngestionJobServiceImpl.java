package com.lzj.rag.backend.ingestion.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lzj.rag.backend.document.service.bo.IngestionSpecBO;
import com.lzj.rag.backend.document.service.internal.DocumentAccess;
import com.lzj.rag.backend.ingestion.config.IngestionProperties;
import com.lzj.rag.backend.ingestion.config.IngestionWorkerProperties;
import com.lzj.rag.backend.ingestion.service.IngestionJobService;
import com.lzj.rag.backend.ingestion.service.bo.IngestionContextBO;
import com.lzj.rag.backend.ingestion.service.bo.IngestionJobBO;
import com.lzj.rag.backend.ingestion.service.bo.IngestionStageBO;
import com.lzj.rag.backend.ingestion.service.bo.IngestionSubmissionBO;
import com.lzj.rag.backend.knowledgebase.exception.KnowledgeBaseException;
import com.lzj.rag.backend.knowledgebase.service.internal.KnowledgeBaseAccess;
import com.lzj.rag.backend.model.service.bo.EmbeddingBindingBO;
import com.lzj.rag.backend.operations.logging.OperationLog;
import com.lzj.rag.backend.security.model.KnowledgeBasePrincipal;
import com.lzj.rag.framework.convention.page.PageResponse;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.time.*;
import java.util.*;

/** 以数据库状态作为入库执行依据；业务锁顺序统一为知识库、文档、任务。 */
@Service
@EnableConfigurationProperties(IngestionWorkerProperties.class)
public class IngestionJobServiceImpl implements IngestionJobService {

    /** 执行数据库查询及受条件保护的业务状态更新。 */
    private final JdbcTemplate jdbc;

    /** 供内部流程校验知识库归属和取得行锁的访问契约。 */
    private final KnowledgeBaseAccess bases;

    /** 供内部流程校验文档归属和取得行锁的访问契约。 */
    private final DocumentAccess documents;

    /** 入库流水线的时间预算、文本长度、切片数量和向量写入限制。 */
    private final IngestionProperties properties;

    /** 入库工作线程、租约、重试退避和失效代次清理的配置。 */
    private final IngestionWorkerProperties worker;

    /** 用于业务请求、持久化快照与元数据转换的 JSON 映射器。 */
    private final ObjectMapper json;

    /** 当前服务实例的后台任务领取标识。 */
    private final String instance = "ingest-" + UUID.randomUUID();

    /** 不包含源内容或供应商详情的对外错误说明。 */
    private static final String SAFE_ERROR =
            "Execution ended; inspect stage and errorCode. Source contents and provider details are omitted.";

    /** 限定运行状态、尝试次数、栅栏令牌和有效租约的条件 SQL。 */
    private static final String OWNED =
            " id=? AND status='RUNNING' AND claim_token=? AND lease_owner=? AND attempts=? AND lease_until>clock_timestamp() AND attempt_deadline>clock_timestamp()";

    /**
     * 初始化当前组件，保存业务依赖及本次使用的配置。
     *
     * @param jdbc 执行数据库查询及受条件保护的业务状态更新。
     * @param bases 供内部流程校验知识库归属和取得行锁的访问契约。
     * @param documents 供内部流程校验文档归属和取得行锁的访问契约。
     * @param properties 入库流水线的时间预算、文本长度、切片数量和向量写入限制。
     * @param worker 入库工作线程、租约、重试退避和失效代次清理的配置。
     * @param json 用于业务请求、持久化快照与元数据转换的 JSON 映射器。
     */
    public IngestionJobServiceImpl(
            JdbcTemplate jdbc,
            KnowledgeBaseAccess bases,
            DocumentAccess documents,
            IngestionProperties properties,
            IngestionWorkerProperties worker,
            ObjectMapper json) {
        this.jdbc = jdbc;
        this.bases = bases;
        this.documents = documents;
        this.properties = properties;
        this.worker = worker;
        this.json = json;
        if (properties.deadline().isNegative()
                || properties.deadline().isZero()
                || properties.deadline().compareTo(Duration.ofMinutes(15)) > 0)
            throw new IllegalArgumentException(
                    "Ingestion attempt deadline must be positive and <=15 minutes");
    }

    /**
     * 提交时固定的源文件信息和嵌入模型绑定，后续尝试从此恢复。
     *
     * @param originalFilename 源文件原始名称。
     * @param mimeType 源文件 MIME 类型。
     * @param fileSize 源文件字节数。
     * @param namespace 向量数据隔离命名空间。
     * @param binding 本次业务固定使用的嵌入模型身份与配置快照。
     */
    private record Snapshot(
            String originalFilename,
            String mimeType,
            long fileSize,
            String namespace,
            EmbeddingBindingBO binding) {}

    /**
     * 校验幂等键和人工内容覆盖确认，复用既有提交或创建持久化入库任务。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param documentId 文档唯一标识。
     * @param requestId 请求跟踪标识或业务幂等键，按当前接口约定使用。
     * @param retryOf 显式重试所对应的原任务标识。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @Transactional(timeout = 10)
    public IngestionSubmissionBO submit(
            KnowledgeBasePrincipal actor, UUID documentId, String requestId, UUID retryOf) {
        return submit(actor, documentId, requestId, retryOf, false, null);
    }

    /**
     * 校验幂等键和人工内容覆盖确认，复用既有提交或创建持久化入库任务。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param documentId 文档唯一标识。
     * @param requestId 请求跟踪标识或业务幂等键，按当前接口约定使用。
     * @param retryOf 显式重试所对应的原任务标识。
     * @param confirmOverwriteEdited 是否已显式确认覆盖人工维护的内容与启停设置。
     * @param expectedDocumentVersion 调用方已审阅并要求匹配的文档版本。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @Transactional(timeout = 10)
    public IngestionSubmissionBO submit(
            KnowledgeBasePrincipal actor,
            UUID documentId,
            String requestId,
            UUID retryOf,
            boolean confirmOverwriteEdited,
            Long expectedDocumentVersion) {
        if (requestId == null || !requestId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{7,127}"))
            throw new KnowledgeBaseException(
                    400, "KB_INVALID_INPUT", "requestId must contain 8..128 safe ASCII characters");
        // 先按调用者与幂等键取得事务咨询锁，再进入业务锁；哈希碰撞只会让无关请求短暂串行。
        jdbc.queryForObject(
                "SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
                Object.class,
                actor.tenantId() + "/" + actor.userId() + "/" + requestId);
        documents.requireOwned(actor, documentId);
        // existing：当前幂等键或业务条件已匹配的既有记录集合。
        var existing =
                jdbc.queryForList(
                        "SELECT * FROM kb_ingestion_job WHERE tenant_id=? AND owner_id=? AND request_id=?",
                        actor.tenantId(),
                        actor.userId(),
                        requestId);
        if (!existing.isEmpty()) {
            // row：当前数据库记录或表格数据行。
            var row = existing.get(0);
            if (!documentId.equals(row.get("document_id"))
                    || !Objects.equals(retryOf, row.get("retry_of"))
                    || confirmOverwriteEdited
                            != Boolean.TRUE.equals(row.get("confirm_overwrite_edited"))
                    || !Objects.equals(
                            expectedDocumentVersion, row.get("expected_document_version")))
                throw new KnowledgeBaseException(
                        409,
                        "KB_IDEMPOTENCY_CONFLICT",
                        "requestId is already bound to another submission");
            return new IngestionSubmissionBO(
                    getOwnedJob(actor, documentId, (UUID) row.get("id")), true);
        }
        if (retryOf != null) {
            // previous：本次修改前的记录或此前已经处理的值。
            var previous = getOwnedJob(actor, documentId, retryOf);
            if (Boolean.TRUE.equals(
                    jdbc.queryForObject(
                            "SELECT refresh_run_id IS NOT NULL FROM kb_ingestion_job WHERE id=?",
                            Boolean.class,
                            retryOf)))
                throw new KnowledgeBaseException(
                        409,
                        "KB_REFRESH_RETRY_REQUIRED",
                        "Retry URL refresh through the document refresh endpoint to refetch and preserve baseline rules");
            if (!Set.of("FAILED", "CANCELLED").contains(previous.status()))
                throw new KnowledgeBaseException(
                        409,
                        "KB_JOB_NOT_RETRYABLE",
                        "Only terminal failed or cancelled jobs can be explicitly retried");
        }
        // id：当前业务对象的唯一标识。
        UUID id =
                createLocked(
                        actor,
                        documentId,
                        requestId,
                        retryOf,
                        worker.maxAttempts(),
                        confirmOverwriteEdited,
                        expectedDocumentVersion,
                        null);
        return new IngestionSubmissionBO(getOwnedJob(actor, documentId, id), false);
    }

    /**
     * 锁定业务输入并校验可执行性，预分配新代次并固化任务输入快照。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param documentId 文档唯一标识。
     * @param requestId 请求跟踪标识或业务幂等键，按当前接口约定使用。
     * @param retryOf 显式重试所对应的原任务标识。
     * @param maxAttempts 允许的最大执行尝试次数。
     * @param confirmOverwriteEdited 是否已显式确认覆盖人工维护的内容与启停设置。
     * @param expectedDocumentVersion 调用方已审阅并要求匹配的文档版本。
     * @param refreshRun 当前关联的刷新运行标识。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private UUID createLocked(
            KnowledgeBasePrincipal actor,
            UUID documentId,
            String requestId,
            UUID retryOf,
            int maxAttempts,
            boolean confirmOverwriteEdited,
            Long expectedDocumentVersion,
            UUID refreshRun) {
        // doc：当前操作涉及的文档。
        var doc = documents.requireOwned(actor, documentId);
        // base：当前操作涉及的知识库及其固定模型绑定。
        var base = bases.lockOwned(actor, doc.getKbId());
        jdbc.queryForObject(
                "SELECT id FROM kb_document WHERE id=? FOR UPDATE", UUID.class, documentId);
        doc = documents.lockOwned(actor, documentId);
        if (Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM kb_refresh_run WHERE document_id=? AND status IN ('PENDING','RUNNING','WAITING_INGESTION') AND (?::uuid IS NULL OR id<>?))",
                        Boolean.class,
                        documentId,
                        refreshRun,
                        refreshRun)))
            throw new KnowledgeBaseException(
                    409,
                    "KB_REFRESH_ACTIVE",
                    "Wait for the active refresh before submitting ingestion");
        // active：当前文档仍未完成的活动任务集合。
        var active =
                jdbc.queryForList(
                        "SELECT id FROM kb_ingestion_job WHERE document_id=? AND status IN ('PENDING','RUNNING')",
                        UUID.class,
                        documentId);
        if (!active.isEmpty())
            throw new KnowledgeBaseException(
                    409,
                    "KB_JOB_ACTIVE",
                    "Document already has active ingestion job " + active.get(0));
        if (!doc.getEnabled())
            throw new KnowledgeBaseException(
                    409, "KB_DOCUMENT_DISABLED", "Enable the document before ingesting");
        if (base.getEmbeddingModelId() == null)
            throw new KnowledgeBaseException(
                    409, "KB_MODEL_UNBOUND", "Bind a model before ingesting");
        if (Boolean.TRUE.equals(doc.getHasManualChanges())
                || Boolean.TRUE.equals(
                        jdbc.queryForObject(
                                "SELECT EXISTS(SELECT 1 FROM kb_chunk WHERE document_id=? AND generation=? AND edited)",
                                Boolean.class,
                                documentId,
                                doc.getActiveGeneration()))) {
            if (!confirmOverwriteEdited || expectedDocumentVersion == null)
                throw new KnowledgeBaseException(
                        409,
                        "KB_MANUAL_OVERWRITE_REQUIRED",
                        "Re-ingestion replaces manual content, additions, deletions and chunk enabled settings; send confirmOverwriteEdited=true and expectedDocumentVersion after review");
        }
        if (expectedDocumentVersion != null && !expectedDocumentVersion.equals(doc.getVersion()))
            throw new KnowledgeBaseException(
                    409,
                    "KB_VERSION_CONFLICT",
                    "Reload the document and review manual changes before confirming replacement");
        // generation：切片或任务所属的数据代次。
        long generation =
                jdbc.queryForObject(
                        "SELECT next_generation+1 FROM kb_document WHERE id=?",
                        Long.class,
                        documentId);
        // id：当前业务对象的唯一标识。
        UUID id = UUID.randomUUID();
        try {
            // model：当前固定的模型配置或 AI 返回的模型身份节点。
            var model = json.readTree(base.getEmbeddingConfigJson());
            // snapshot：本次模型调用或状态变更所依据的不可变业务快照。
            var snapshot =
                    new Snapshot(
                            doc.getOriginalFilename(),
                            doc.getMimeType(),
                            doc.getFileSize(),
                            base.getVectorNamespace(),
                            new EmbeddingBindingBO(
                                    base.getEmbeddingModelId(),
                                    base.getEmbeddingProvider(),
                                    model.path("modelName").asText(),
                                    base.getEmbeddingDimension(),
                                    model.path("embeddingSpace").asText(),
                                    model.path("configVersion").asText(),
                                    base.getEmbeddingConfigRevision(),
                                    base.getEmbeddingAiConfigRevision()));
            jdbc.update(
                    """
                INSERT INTO kb_ingestion_job(id,request_id,document_id,kb_id,tenant_id,owner_id,document_config_revision,document_version,
                embedding_config_revision,target_generation,object_key,content_hash,ingestion_spec,status,stage,attempts,max_attempts,next_run_at,claim_token,input_snapshot,retry_of,confirm_overwrite_edited,expected_document_version)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,'PENDING','FETCH',0,?,clock_timestamp(),0,?::jsonb,?,?,?)
                """,
                    id,
                    requestId,
                    documentId,
                    doc.getKbId(),
                    actor.tenantId(),
                    actor.userId(),
                    doc.getConfigRevision(),
                    doc.getVersion(),
                    base.getEmbeddingConfigRevision(),
                    generation,
                    doc.getObjectKey(),
                    doc.getContentHash(),
                    doc.getIngestionSpec(),
                    maxAttempts,
                    json.writeValueAsString(snapshot),
                    retryOf,
                    confirmOverwriteEdited,
                    expectedDocumentVersion);
            jdbc.update(
                    "UPDATE kb_document SET next_generation=?,latest_job_id=?,process_status='PROCESSING',updated_at=clock_timestamp() WHERE id=?",
                    generation,
                    id,
                    documentId);
            return id;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // e：本次捕获或匹配的异常，用于保留业务分类及失败原因。
            throw new IllegalStateException("Invalid persisted ingestion input", e);
        }
    }

    /**
     * 基于刷新候选对象创建标准入库任务，并关联刷新运行与租约令牌。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param run 当前刷新运行记录或运行标识。
     * @param token 当前任务的租约栅栏令牌或清理凭据。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @Transactional(timeout = 10)
    public UUID submitRefresh(KnowledgeBasePrincipal actor, Map<String, Object> run, long token) {
        // runId：刷新运行唯一标识。
        UUID runId = (UUID) run.get("id");
        // document：本次操作涉及的文档唯一标识。
        UUID document = (UUID) run.get("document_id");
        // id：当前业务对象的唯一标识。
        UUID id =
                createLocked(
                        actor,
                        document,
                        "refresh-" + runId + "-" + token,
                        null,
                        worker.maxAttempts(),
                        Boolean.TRUE.equals(run.get("confirm_overwrite_edited")),
                        (Long) run.get("expected_document_version"),
                        runId);
        jdbc.update(
                """
            UPDATE kb_ingestion_job SET object_key=?,content_hash=?,refresh_run_id=?,refresh_claim_token=?,
            input_snapshot=input_snapshot || jsonb_build_object('originalFilename',?::text,'mimeType',?::text,'fileSize',?::bigint) WHERE id=?
            """,
                run.get("candidate_object_key"),
                run.get("candidate_hash"),
                runId,
                token,
                run.get("candidate_filename"),
                run.get("candidate_mime"),
                run.get("candidate_size"),
                id);
        jdbc.update(
                "UPDATE kb_refresh_run SET job_id=?,status='WAITING_INGESTION' WHERE id=?",
                id,
                runId);
        return id;
    }

    /**
     * 为内部同步入口创建单次尝试任务，并立即领取相同的数据库执行凭据。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param documentId 文档唯一标识。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @Transactional(timeout = 10)
    public IngestionContextBO start(KnowledgeBasePrincipal actor, UUID documentId) {
        // doc：当前操作涉及的文档。
        var doc = documents.requireOwned(actor, documentId);
        bases.lockOwned(actor, doc.getKbId());
        jdbc.queryForObject(
                "SELECT id FROM kb_document WHERE id=? FOR UPDATE", UUID.class, documentId);
        recoverLocked(documentId);
        // id：当前业务对象的唯一标识。
        UUID id =
                createLocked(
                        actor,
                        documentId,
                        "internal-" + UUID.randomUUID(),
                        null,
                        1,
                        false,
                        null,
                        null);
        return claimLocked(
                jdbc.queryForMap("SELECT * FROM kb_ingestion_job WHERE id=? FOR UPDATE", id));
    }

    /**
     * 以非阻塞锁领取到期任务，先收敛失效租约，再创建新的执行尝试。
     *
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @Transactional(timeout = 10)
    public IngestionContextBO claimNext() {
        // candidates：本次待领取的任务、可选解析策略或召回候选集合。
        var candidates =
                jdbc.queryForList(
                        """
            SELECT id,kb_id,document_id FROM kb_ingestion_job WHERE
            (status='PENDING' AND next_run_at<=clock_timestamp()) OR (status='RUNNING' AND lease_until<=clock_timestamp())
            ORDER BY COALESCE(next_run_at,lease_until),created_at,id LIMIT 64
            """);
        // candidate：当前需要领取的后台任务或参与融合的召回候选。
        for (var candidate : candidates) {
            if (jdbc.queryForList(
                            "SELECT id FROM kb_knowledge_base WHERE id=? FOR UPDATE SKIP LOCKED",
                            UUID.class,
                            candidate.get("kb_id"))
                    .isEmpty()) continue;
            if (jdbc.queryForList(
                            "SELECT id FROM kb_document WHERE id=? FOR UPDATE SKIP LOCKED",
                            UUID.class,
                            candidate.get("document_id"))
                    .isEmpty()) continue;
            // rows：数据库结果或当前表格数据行集合。
            var rows =
                    jdbc.queryForList(
                            """
                SELECT * FROM kb_ingestion_job WHERE id=? AND
                ((status='PENDING' AND next_run_at<=clock_timestamp()) OR (status='RUNNING' AND lease_until<=clock_timestamp())) FOR UPDATE SKIP LOCKED
                """,
                            candidate.get("id"));
            if (rows.isEmpty()) continue;
            // row：当前数据库记录或表格数据行。
            var row = rows.get(0);
            if ("RUNNING".equals(row.get("status"))) {
                finishAttempt(row, "KB_INGESTION_EXPIRED", true);
                continue;
            }
            // claimed：成功领取后携带有效执行凭据的任务记录。
            var claimed = claimLocked(row);
            if (claimed != null) return claimed;
        }
        return null;
    }

    /**
     * 复核文档和模型快照，推进尝试次数及栅栏令牌并建立有效租约。
     *
     * @param row 当前数据库记录或表格数据行。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private IngestionContextBO claimLocked(Map<String, Object> row) {
        // doc：当前操作涉及的文档。
        var doc = jdbc.queryForMap("SELECT * FROM kb_document WHERE id=?", row.get("document_id"));
        // base：当前操作涉及的知识库及其固定模型绑定。
        var base = jdbc.queryForMap("SELECT * FROM kb_knowledge_base WHERE id=?", row.get("kb_id"));
        if (doc.get("deleted_at") != null
                || base.get("deleted_at") != null
                || !Boolean.TRUE.equals(doc.get("enabled"))
                || !row.get("id").equals(doc.get("latest_job_id"))
                || !row.get("document_version").equals(doc.get("version"))
                || !row.get("document_config_revision").equals(doc.get("config_revision"))
                || !row.get("embedding_config_revision")
                        .equals(base.get("embedding_config_revision"))) {
            terminal(row, "CANCELLED", "KB_INGESTION_INPUT_CHANGED");
            return null;
        }
        // claimed：成功领取后携带有效执行凭据的任务记录。
        var claimed =
                jdbc.queryForMap(
                        """
            UPDATE kb_ingestion_job SET status='RUNNING',stage='FETCH',attempts=attempts+1,claim_token=claim_token+1,lease_owner=?,
            lease_until=clock_timestamp()+(?*interval '1 millisecond'),attempt_deadline=clock_timestamp()+(?*interval '1 millisecond'),
            started_at=COALESCE(started_at,clock_timestamp()),execution_started_at=NULL,next_run_at=NULL,completed_at=NULL,error_code=NULL,error_message=NULL
            WHERE id=? AND status='PENDING' AND claim_token=? AND attempts<max_attempts RETURNING *
            """,
                        instance,
                        Math.min(worker.lease().toMillis(), properties.deadline().toMillis()),
                        properties.deadline().toMillis(),
                        row.get("id"),
                        row.get("claim_token"));
        return context(claimed);
    }

    /**
     * 从持久化快照和租约记录还原本次执行上下文。
     *
     * @param row 当前数据库记录或表格数据行。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private IngestionContextBO context(Map<String, Object> row) {
        try {
            // input：本阶段需要处理的输入内容或固定输入快照。
            var input = json.readValue(row.get("input_snapshot").toString(), Snapshot.class);
            // actor：本次业务操作的租户及用户身份。
            var actor =
                    new KnowledgeBasePrincipal(
                            "persisted-ingestion",
                            "unused",
                            (String) row.get("tenant_id"),
                            (String) row.get("owner_id"));
            // deadline：本阶段或本次请求必须完成的截止时间。
            Object deadline = row.get("attempt_deadline");
            // offset：分页偏移量或数据库时间的带偏移表示。
            // instant：从数据库时间值转换得到的绝对时刻。
            Instant instant =
                    deadline instanceof OffsetDateTime offset
                            ? offset.toInstant()
                            : ((java.sql.Timestamp) deadline).toInstant();
            return new IngestionContextBO(
                    (UUID) row.get("id"),
                    (String) row.get("request_id"),
                    (UUID) row.get("document_id"),
                    (UUID) row.get("kb_id"),
                    actor,
                    ((Number) row.get("document_version")).longValue(),
                    ((Number) row.get("document_config_revision")).longValue(),
                    ((Number) row.get("target_generation")).longValue(),
                    (String) row.get("object_key"),
                    input.originalFilename(),
                    input.mimeType(),
                    input.fileSize(),
                    (String) row.get("content_hash"),
                    json.readValue(row.get("ingestion_spec").toString(), IngestionSpecBO.class),
                    input.binding(),
                    input.namespace(),
                    instant,
                    ((Number) row.get("attempts")).intValue(),
                    ((Number) row.get("claim_token")).longValue(),
                    (String) row.get("lease_owner"));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // e：本次捕获或匹配的异常，用于保留业务分类及失败原因。
            throw new IllegalStateException("Invalid persisted job snapshot", e);
        }
    }

    /**
     * 以数据库条件更新确保同一执行凭据只进入流水线一次。
     *
     * @param c 本次入库尝试固定的输入快照及租约凭据。
     */
    public void enter(IngestionContextBO c) {
        if (jdbc.update(
                        "UPDATE kb_ingestion_job SET execution_started_at=clock_timestamp() WHERE"
                                + OWNED
                                + " AND execution_started_at IS NULL",
                        c.jobId(),
                        c.claimToken(),
                        c.leaseOwner(),
                        c.attempt())
                != 1) throw lost();
    }

    /**
     * 仅延长本工作线程仍有效的租约，截止时间之后不能恢复执行权。
     *
     * @param c 本次入库尝试固定的输入快照及租约凭据。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public boolean renew(IngestionContextBO c) {
        return jdbc.update(
                        "UPDATE kb_ingestion_job SET lease_until=LEAST(attempt_deadline,clock_timestamp()+(?*interval '1 millisecond')) WHERE"
                                + OWNED,
                        worker.lease().toMillis(),
                        c.jobId(),
                        c.claimToken(),
                        c.leaseOwner(),
                        c.attempt())
                == 1;
    }

    /**
     * 工作线程拒绝接收任务时释放尚未进入流水线的领取记录。
     *
     * @param c 本次入库尝试固定的输入快照及租约凭据。
     */
    public void releaseUnstarted(IngestionContextBO c) {
        jdbc.update(
                "UPDATE kb_ingestion_job SET status='PENDING',attempts=attempts-1,claim_token=claim_token+1,lease_owner=NULL,lease_until=NULL,attempt_deadline=NULL,next_run_at=clock_timestamp() WHERE"
                        + OWNED
                        + " AND execution_started_at IS NULL",
                c.jobId(),
                c.claimToken(),
                c.leaseOwner(),
                c.attempt());
    }

    /**
     * 在当前有效执行凭据下记录阶段开始时间及输入计数。
     *
     * @param c 本次入库尝试固定的输入快照及租约凭据。
     * @param stage 当前流水线阶段标识。
     * @param inputs 当前阶段累计处理的输入数量。
     * @param unit 计数或来源行号所使用的单位。
     */
    @Transactional(timeout = 10)
    public void begin(IngestionContextBO c, String stage, long inputs, String unit) {
        if (jdbc.update(
                        "UPDATE kb_ingestion_job SET stage=? WHERE" + OWNED,
                        stage,
                        c.jobId(),
                        c.claimToken(),
                        c.leaseOwner(),
                        c.attempt())
                != 1) throw lost();
        jdbc.update(
                "INSERT INTO kb_ingestion_stage_log(job_id,attempt,stage,status,input_count,input_unit,started_at) VALUES (?,?,?,'RUNNING',?,?,clock_timestamp())",
                c.jobId(),
                c.attempt(),
                stage,
                inputs,
                unit);
        OperationLog.committed(
                c.kbId(), c.documentId(), c.jobId(), c.generation(), stage, "RUNNING");
    }

    /**
     * 在当前有效执行凭据下记录阶段完成时间及输出计数。
     *
     * @param c 本次入库尝试固定的输入快照及租约凭据。
     * @param stage 当前流水线阶段标识。
     * @param outputs 当前阶段累计生成的输出数量。
     * @param unit 计数或来源行号所使用的单位。
     */
    @Transactional(timeout = 10)
    public void end(IngestionContextBO c, String stage, long outputs, String unit) {
        if (jdbc.update(
                        "UPDATE kb_ingestion_job SET stage=stage WHERE" + OWNED,
                        c.jobId(),
                        c.claimToken(),
                        c.leaseOwner(),
                        c.attempt())
                != 1) throw lost();
        jdbc.update(
                """
            UPDATE kb_ingestion_stage_log SET status='SUCCEEDED',ended_at=clock_timestamp(),
            duration_ms=GREATEST(0,(EXTRACT(EPOCH FROM (clock_timestamp()-started_at))*1000)::bigint),output_count=?,output_unit=?
            WHERE job_id=? AND attempt=? AND stage=? AND status='RUNNING'
            """,
                outputs,
                unit,
                c.jobId(),
                c.attempt(),
                stage);
        OperationLog.committed(
                c.kbId(), c.documentId(), c.jobId(), c.generation(), stage, "SUCCEEDED");
    }

    /**
     * 将本次实际使用的 AI 配置修订固定到任务，防止尝试内身份漂移。
     *
     * @param c 本次入库尝试固定的输入快照及租约凭据。
     * @param revision 当前同步数据或配置使用的修订标识。
     */
    public void pinAiRevision(IngestionContextBO c, String revision) {
        if (jdbc.update(
                        "UPDATE kb_ingestion_job SET ai_config_revision=? WHERE" + OWNED,
                        revision,
                        c.jobId(),
                        c.claimToken(),
                        c.leaseOwner(),
                        c.attempt())
                != 1) throw lost();
    }

    /**
     * 在仍持有租约时归类执行错误，决定退避重试或进入终态。
     *
     * @param c 本次入库尝试固定的输入快照及租约凭据。
     * @param error 本次处理捕获的异常，用于归类失败原因。
     */
    @Transactional(timeout = 10)
    public void fail(IngestionContextBO c, RuntimeException error) {
        // 进程关闭或线程中断不代表用户取消，保留持久化租约以供恢复。
        if (Thread.currentThread().isInterrupted()) return;
        // 失效、取消或被新尝试替代的工作线程也不能覆盖当前失败状态。
        jdbc.queryForObject(
                "SELECT id FROM kb_knowledge_base WHERE id=? FOR UPDATE", UUID.class, c.kbId());
        jdbc.queryForObject(
                "SELECT id FROM kb_document WHERE id=? FOR UPDATE", UUID.class, c.documentId());
        // rows：数据库结果或当前表格数据行集合。
        var rows =
                jdbc.queryForList(
                        "SELECT * FROM kb_ingestion_job WHERE" + OWNED + " FOR UPDATE",
                        c.jobId(),
                        c.claimToken(),
                        c.leaseOwner(),
                        c.attempt());
        if (rows.isEmpty()) return;
        // kb：本次捕获或匹配的异常，用于保留业务分类及失败原因。
        // code：对外使用的稳定业务错误分类。
        String code =
                error instanceof KnowledgeBaseException kb ? kb.code() : "KB_INGESTION_FAILED";
        if (Set.of("KB_JOB_CANCELLED", "KB_INGESTION_INPUT_CHANGED").contains(code)) {
            terminal(rows.get(0), "CANCELLED", code);
            return;
        }
        // retryable：该错误是否允许在预算和次数限制内重试。
        boolean retryable =
                Set.of(
                                        "KB_MODEL_RATE_LIMITED",
                                        "KB_MODEL_UNAVAILABLE",
                                        "KB_MODEL_TIMEOUT",
                                        "KB_INGESTION_TIMEOUT",
                                        "KB_STORAGE_UNAVAILABLE",
                                        "KB_PARSE_SERVICE_UNAVAILABLE",
                                        "KB_EXTERNAL_PARSE_TIMEOUT")
                                .contains(code)
                        || error instanceof TransientDataAccessException
                        || error
                                instanceof
                                org.springframework.dao.DataAccessResourceFailureException;
        finishAttempt(rows.get(0), code, retryable);
    }

    /**
     * 结束当前尝试，关闭阶段并按错误类型和次数安排后续重试。
     *
     * @param row 当前数据库记录或表格数据行。
     * @param code 对外使用的稳定业务错误分类。
     * @param retryable 该错误是否允许在预算和次数限制内重试。
     */
    private void finishAttempt(Map<String, Object> row, String code, boolean retryable) {
        // attempt：当前执行尝试的序号。
        int attempt = ((Number) row.get("attempts")).intValue();
        closeStages(row, "FAILED", code);
        if (retryable && attempt < ((Number) row.get("max_attempts")).intValue()) {
            jdbc.update(
                    """
                UPDATE kb_ingestion_job SET status='PENDING',next_run_at=clock_timestamp()+(?*interval '1 millisecond'),claim_token=claim_token+1,
                lease_owner=NULL,lease_until=NULL,attempt_deadline=NULL,error_code=?,error_message=? WHERE id=? AND claim_token=? AND status='RUNNING'
                """,
                    worker.backoffMillis(attempt),
                    code,
                    SAFE_ERROR,
                    row.get("id"),
                    row.get("claim_token"));
        } else terminal(row, "FAILED", code);
    }

    /**
     * 将任务置为终态，同时收敛文档状态并登记未发布代次清理。
     *
     * @param row 当前数据库记录或表格数据行。
     * @param status 当前业务操作或任务状态。
     * @param code 对外使用的稳定业务错误分类。
     */
    private void terminal(Map<String, Object> row, String status, String code) {
        jdbc.update(
                """
            UPDATE kb_ingestion_job SET status=?,completed_at=clock_timestamp(),next_run_at=NULL,claim_token=claim_token+1,
            lease_owner=NULL,lease_until=NULL,attempt_deadline=NULL,error_code=?,error_message=? WHERE id=? AND claim_token=? AND status=?
            """,
                status,
                code,
                SAFE_ERROR,
                row.get("id"),
                row.get("claim_token"),
                row.get("status"));
        closeStages(row, status, code);
        jdbc.update(
                """
            UPDATE kb_document SET process_status=CASE WHEN version<>? OR ?='CANCELLED' THEN 'PENDING' ELSE 'FAILED' END,updated_at=clock_timestamp()
            WHERE id=? AND latest_job_id=?
            """,
                row.get("document_version"),
                status,
                row.get("document_id"),
                row.get("id"));
        queueCleanup(
                (UUID) row.get("document_id"), ((Number) row.get("target_generation")).longValue());
    }

    /**
     * 关闭当前尝试尚未结束的阶段，统一写入终态及错误码。
     *
     * @param row 当前数据库记录或表格数据行。
     * @param status 当前业务操作或任务状态。
     * @param code 对外使用的稳定业务错误分类。
     */
    private void closeStages(Map<String, Object> row, String status, String code) {
        jdbc.update(
                """
            UPDATE kb_ingestion_stage_log SET status=?,ended_at=clock_timestamp(),error_code=?,error_message=?,
            duration_ms=GREATEST(0,(EXTRACT(EPOCH FROM (clock_timestamp()-started_at))*1000)::bigint)
            WHERE job_id=? AND attempt=? AND status='RUNNING'
            """,
                status,
                code,
                SAFE_ERROR,
                row.get("id"),
                row.get("attempts"));
    }

    /**
     * 校验归属并使活动任务进入取消状态，令旧执行凭据立即失效。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param jobId 持久化入库任务标识。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @Transactional(timeout = 10)
    public IngestionJobBO cancel(KnowledgeBasePrincipal actor, UUID jobId) {
        // job：当前入库任务记录或任务标识。
        var job = getOwnedJob(actor, jobId);
        // doc：当前操作涉及的文档。
        var doc = documents.requireOwned(actor, job.documentId());
        bases.lockOwned(actor, doc.getKbId());
        jdbc.queryForObject(
                "SELECT id FROM kb_document WHERE id=? FOR UPDATE", UUID.class, job.documentId());
        // row：当前数据库记录或表格数据行。
        var row = jdbc.queryForMap("SELECT * FROM kb_ingestion_job WHERE id=? FOR UPDATE", jobId);
        if (Set.of("PENDING", "RUNNING").contains(row.get("status")))
            terminal(row, "CANCELLED", "KB_JOB_CANCELLED");
        return getOwnedJob(actor, job.documentId(), jobId);
    }

    /**
     * 在文档业务锁内收敛已过期的运行尝试。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param documentId 文档唯一标识。
     */
    @Transactional(timeout = 10)
    public void recoverExpired(KnowledgeBasePrincipal actor, UUID documentId) {
        // doc：当前操作涉及的文档。
        var doc = documents.requireOwned(actor, documentId);
        bases.lockOwned(actor, doc.getKbId());
        jdbc.queryForObject(
                "SELECT id FROM kb_document WHERE id=? FOR UPDATE", UUID.class, documentId);
        recoverLocked(documentId);
    }

    /**
     * 为指定文档处理过期任务并登记失败尝试。
     *
     * @param documentId 文档唯一标识。
     */
    private void recoverLocked(UUID documentId) {
        // row：当前数据库记录或表格数据行。
        for (var row :
                jdbc.queryForList(
                        "SELECT * FROM kb_ingestion_job WHERE document_id=? AND status='RUNNING' AND lease_until<=clock_timestamp() FOR UPDATE",
                        documentId)) finishAttempt(row, "KB_INGESTION_EXPIRED", true);
    }

    /**
     * 为未发布或失效代次登记可重试的清理任务。
     *
     * @param documentId 文档唯一标识。
     * @param generation 切片或任务所属的数据代次。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void queueCleanup(UUID documentId, long generation) {
        jdbc.update(
                "INSERT INTO kb_generation_cleanup(id,document_id,generation,next_run_at) VALUES (?,?,?,clock_timestamp()+(?*interval '1 millisecond')) ON CONFLICT(document_id,generation) DO NOTHING",
                UUID.randomUUID(),
                documentId,
                generation,
                worker.cleanupDelay().toMillis());
    }

    /**
     * 创建当前工作线程已失去执行租约的异常。
     *
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private static KnowledgeBaseException lost() {
        return new KnowledgeBaseException(
                409, "KB_JOB_EXPIRED", "This execution no longer owns an active job lease");
    }

    /**
     * 在当前用户及可选文档范围内读取任务详情。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param jobId 持久化入库任务标识。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public IngestionJobBO getOwnedJob(KnowledgeBasePrincipal actor, UUID jobId) {
        // ids：本次操作涉及的业务对象标识集合。
        var ids =
                jdbc.queryForList(
                        "SELECT document_id FROM kb_ingestion_job WHERE id=? AND tenant_id=? AND owner_id=?",
                        UUID.class,
                        jobId,
                        actor.tenantId(),
                        actor.userId());
        if (ids.isEmpty())
            throw new KnowledgeBaseException(404, "KB_JOB_NOT_FOUND", "Ingestion job not found");
        return getOwnedJob(actor, ids.get(0), jobId);
    }

    /**
     * 在当前用户及可选文档范围内读取任务详情。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param documentId 文档唯一标识。
     * @param jobId 持久化入库任务标识。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public IngestionJobBO getOwnedJob(KnowledgeBasePrincipal actor, UUID documentId, UUID jobId) {
        documents.requireOwned(actor, documentId);
        // rows：数据库结果或当前表格数据行集合。
        var rows =
                jdbc.query(
                        "SELECT * FROM kb_ingestion_job WHERE id=? AND document_id=? AND tenant_id=? AND owner_id=?",
                        IngestionJobServiceImpl::jobView,
                        jobId,
                        documentId,
                        actor.tenantId(),
                        actor.userId());
        if (rows.isEmpty())
            throw new KnowledgeBaseException(404, "KB_JOB_NOT_FOUND", "Ingestion job not found");
        return rows.get(0);
    }

    /**
     * 按文档与任务状态分页查询当前用户的入库任务。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param documentId 文档唯一标识。
     * @param current 当前分页页码，从一开始。
     * @param size 分页容量或当前数据的字节数。
     * @param status 当前业务操作或任务状态。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 10)
    public PageResponse<IngestionJobBO> list(
            KnowledgeBasePrincipal actor, UUID documentId, int current, int size, String status) {
        documents.requireOwned(actor, documentId);
        if (!status.isEmpty()
                && !Set.of("PENDING", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED")
                        .contains(status))
            throw new KnowledgeBaseException(400, "KB_INVALID_INPUT", "Unknown job status");
        // filter：包含租户、所有者及当前业务筛选条件的查询约束。
        String filter =
                " WHERE document_id=? AND tenant_id=? AND owner_id=? AND (?='' OR status=?)";
        // total：满足当前查询条件的记录总数。
        Long total =
                jdbc.queryForObject(
                        "SELECT count(*) FROM kb_ingestion_job" + filter,
                        Long.class,
                        documentId,
                        actor.tenantId(),
                        actor.userId(),
                        status,
                        status);
        // records：当前存储记录集合或证据重载组件。
        var records =
                jdbc.query(
                        "SELECT * FROM kb_ingestion_job"
                                + filter
                                + " ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
                        IngestionJobServiceImpl::jobView,
                        documentId,
                        actor.tenantId(),
                        actor.userId(),
                        status,
                        status,
                        size,
                        ((long) current - 1) * size);
        return new PageResponse<>(current, size, total, records);
    }

    /**
     * 分页读取指定入库任务各次尝试的阶段记录。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param documentId 文档唯一标识。
     * @param jobId 持久化入库任务标识。
     * @param current 当前分页页码，从一开始。
     * @param size 分页容量或当前数据的字节数。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 10)
    public PageResponse<IngestionStageBO> stages(
            KnowledgeBasePrincipal actor, UUID documentId, UUID jobId, int current, int size) {
        getOwnedJob(actor, documentId, jobId);
        // total：满足当前查询条件的记录总数。
        Long total =
                jdbc.queryForObject(
                        "SELECT count(*) FROM kb_ingestion_stage_log WHERE job_id=?",
                        Long.class,
                        jobId);
        // rs：数据库查询的当前结果行。
        // n：当前结果行的顺序编号，由 JDBC 行映射器传入。
        // records：当前存储记录集合或证据重载组件。
        var records =
                jdbc.query(
                        """
            SELECT * FROM kb_ingestion_stage_log WHERE job_id=? ORDER BY attempt,
            array_position(ARRAY['FETCH','PARSE','SPLIT','EMBED','PERSIST','PUBLISH'],stage) LIMIT ? OFFSET ?
            """,
                        (rs, n) ->
                                new IngestionStageBO(
                                        rs.getString("stage"),
                                        rs.getInt("attempt"),
                                        rs.getString("status"),
                                        rs.getObject("started_at", OffsetDateTime.class),
                                        rs.getObject("ended_at", OffsetDateTime.class),
                                        rs.getObject("duration_ms", Long.class),
                                        rs.getObject("input_count", Long.class),
                                        rs.getString("input_unit"),
                                        rs.getObject("output_count", Long.class),
                                        rs.getString("output_unit"),
                                        rs.getString("error_code"),
                                        rs.getString("error_message")),
                        jobId,
                        size,
                        ((long) current - 1) * size);
        return new PageResponse<>(current, size, total, records);
    }

    /**
     * 将数据库任务记录转换为任务查询响应。
     *
     * @param rs 数据库查询的当前结果行。
     * @param row 当前数据库记录或表格数据行。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private static IngestionJobBO jobView(java.sql.ResultSet rs, int row)
            throws java.sql.SQLException {
        return new IngestionJobBO(
                rs.getObject("id", UUID.class),
                rs.getString("request_id"),
                rs.getObject("document_id", UUID.class),
                rs.getLong("document_config_revision"),
                rs.getLong("target_generation"),
                rs.getString("job_type"),
                rs.getString("status"),
                rs.getString("stage"),
                rs.getInt("attempts"),
                rs.getInt("max_attempts"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("started_at", OffsetDateTime.class),
                rs.getObject("completed_at", OffsetDateTime.class),
                rs.getObject("next_run_at", OffsetDateTime.class),
                rs.getObject("lease_until", OffsetDateTime.class),
                rs.getString("error_code"),
                rs.getString("error_message"),
                rs.getString("ai_config_revision"),
                rs.getObject("retry_of", UUID.class));
    }
}
