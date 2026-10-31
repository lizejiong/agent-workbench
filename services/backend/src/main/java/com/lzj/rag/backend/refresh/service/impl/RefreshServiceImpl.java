package com.lzj.rag.backend.refresh.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lzj.rag.backend.document.dao.entity.DocumentDO;
import com.lzj.rag.backend.document.service.bo.IngestionSpecBO;
import com.lzj.rag.backend.document.service.internal.DocumentAccess;
import com.lzj.rag.backend.document.storage.DocumentStorage;
import com.lzj.rag.backend.document.upload.PreparedUpload;
import com.lzj.rag.backend.infra.storage.config.StorageProperties;
import com.lzj.rag.backend.ingestion.service.IngestionJobService;
import com.lzj.rag.backend.knowledgebase.exception.KnowledgeBaseException;
import com.lzj.rag.backend.knowledgebase.service.internal.KnowledgeBaseAccess;
import com.lzj.rag.backend.parser.routing.ParserRegistry;
import com.lzj.rag.backend.refresh.config.RemoteProperties;
import com.lzj.rag.backend.refresh.download.RemoteDownloader;
import com.lzj.rag.backend.refresh.service.RefreshService;
import com.lzj.rag.backend.refresh.service.bo.RefreshBO;
import com.lzj.rag.backend.refresh.service.bo.RefreshClaimBO;
import com.lzj.rag.backend.refresh.service.bo.RefreshRunBO;
import com.lzj.rag.backend.refresh.service.bo.RefreshScheduleBO;
import com.lzj.rag.backend.refresh.service.bo.RefreshScheduleResultBO;
import com.lzj.rag.backend.refresh.service.bo.RefreshSubmittedBO;
import com.lzj.rag.backend.refresh.state.RefreshState;
import com.lzj.rag.backend.security.model.KnowledgeBasePrincipal;
import com.lzj.rag.framework.convention.page.PageResponse;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.*;
import java.util.*;

/** 协调远程文档刷新计划、租约、条件下载及后续入库任务。 */
@Service
public class RefreshServiceImpl implements RefreshService {

    /** 执行数据库查询及受条件保护的业务状态更新。 */
    private final JdbcTemplate jdbc;

    /** 供内部流程校验文档归属和取得行锁的访问契约。 */
    private final DocumentAccess documents;

    /** 供内部流程校验知识库归属和取得行锁的访问契约。 */
    private final KnowledgeBaseAccess bases;

    /** 负责持久化入库任务及租约状态迁移的业务服务。 */
    private final IngestionJobService jobs;

    /** 负责刷新凭据、源对象切换及终态维护的组件。 */
    private final RefreshState state;

    /** 执行网络许可校验和有界下载的组件。 */
    private final RemoteDownloader downloader;

    /** 支持流式读写及幂等删除的源对象存储组件。 */
    private final DocumentStorage storage;

    /** 对象存储连接、文件容量、临时目录及上传补偿配置。 */
    private final StorageProperties storageConfig;

    /** 远程导入的网络许可、下载容量、并发、租约和重试配置。 */
    private final RemoteProperties config;

    /** 按真实格式和处理档位选择解析策略的注册器。 */
    private final ParserRegistry parsers;

    /** 用于业务请求、持久化快照与元数据转换的 JSON 映射器。 */
    private final ObjectMapper json;

    /** 用于短时状态变更的事务模板。 */
    private final TransactionTemplate tx;

    /** 当前服务实例的后台任务领取标识。 */
    private final String instance = "refresh-" + UUID.randomUUID();

    /**
     * 初始化当前组件，保存业务依赖及本次使用的配置。
     *
     * @param jdbc 执行数据库查询及受条件保护的业务状态更新。
     * @param documents 供内部流程校验文档归属和取得行锁的访问契约。
     * @param bases 供内部流程校验知识库归属和取得行锁的访问契约。
     * @param jobs 负责持久化入库任务及租约状态迁移的业务服务。
     * @param state 负责刷新凭据、源对象切换及终态维护的组件。
     * @param downloader 执行网络许可校验和有界下载的组件。
     * @param storage 支持流式读写及幂等删除的源对象存储组件。
     * @param storageConfig 对象存储连接、文件容量、临时目录及上传补偿配置。
     * @param config 远程导入的网络许可、下载容量、并发、租约和重试配置。
     * @param parsers 按真实格式和处理档位选择解析策略的注册器。
     * @param json 用于业务请求、持久化快照与元数据转换的 JSON 映射器。
     * @param transactions 创建后台短事务及只读事务的事务管理器。
     */
    public RefreshServiceImpl(
            JdbcTemplate jdbc,
            DocumentAccess documents,
            KnowledgeBaseAccess bases,
            IngestionJobService jobs,
            RefreshState state,
            RemoteDownloader downloader,
            DocumentStorage storage,
            StorageProperties storageConfig,
            RemoteProperties config,
            ParserRegistry parsers,
            ObjectMapper json,
            PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.documents = documents;
        this.bases = bases;
        this.jobs = jobs;
        this.state = state;
        this.downloader = downloader;
        this.storage = storage;
        this.storageConfig = storageConfig;
        this.config = config;
        this.parsers = parsers;
        this.json = json;
        tx = new TransactionTemplate(transactions);
        tx.setTimeout(10);
    }

    /**
     * 查询当前用户 URL 文档的刷新计划，缺省时初始化计划记录。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param document 本次操作涉及的文档唯一标识。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @Transactional(readOnly = true, timeout = 10)
    public RefreshScheduleResultBO schedule(KnowledgeBasePrincipal actor, UUID document) {
        requireUrl(actor, document);
        return scheduleView(document);
    }

    /**
     * 校验 Cron、时区和预期版本后修改刷新计划，取消已失效运行。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param document 本次操作涉及的文档唯一标识。
     * @param request 当前入口接收的请求参数。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @Transactional(timeout = 10)
    public RefreshScheduleResultBO configure(
            KnowledgeBasePrincipal actor, UUID document, RefreshScheduleBO request) {
        // doc：当前操作涉及的文档。
        var doc = lock(actor, document);
        // current：分页页码或当前处理的中间对象。
        var current =
                jdbc.queryForMap(
                        "SELECT * FROM kb_refresh_schedule WHERE document_id=? FOR UPDATE",
                        document);
        if (((Number) current.get("version")).longValue() != request.version())
            throw conflict("KB_VERSION_CONFLICT", "Reload the current refresh schedule");
        // next：下一次触发时间或下一跳远程地址。
        var next = RefreshState.next(request.cron(), request.timezone(), state.now());
        if (request.enabled()) {
            downloader.validateUrl(doc.getSourceUrl());
            if (!doc.getEnabled())
                throw conflict("KB_DOCUMENT_DISABLED", "Enable the document before its schedule");
            if (activeRefresh(document))
                throw conflict(
                        "KB_REFRESH_ACTIVE",
                        "Wait for active refresh before changing an enabled schedule");
        } else {
            cancelRunJobs(actor, document);
            // run：当前刷新运行记录或运行标识。
            for (var run :
                    jdbc.queryForList(
                            "SELECT * FROM kb_refresh_run WHERE document_id=? AND status IN ('PENDING','RUNNING','WAITING_INGESTION') FOR UPDATE",
                            document)) state.finish(run, "SKIPPED", "SCHEDULE_DISABLED");
        }
        jdbc.update(
                "UPDATE kb_refresh_schedule SET enabled=?,cron_expression=?,timezone=?,version=version+1,next_run_at=?,claim_token=claim_token+1,updated_at=clock_timestamp() WHERE document_id=?",
                request.enabled(),
                request.cron(),
                request.timezone(),
                request.enabled() ? next : null,
                document);
        return scheduleView(document);
    }

    /**
     * 校验幂等键和人工内容覆盖确认，创建或复用手动刷新运行。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param document 本次操作涉及的文档唯一标识。
     * @param request 当前入口接收的请求参数。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @Transactional(timeout = 10)
    public RefreshSubmittedBO submit(
            KnowledgeBasePrincipal actor, UUID document, RefreshBO request) {
        // 幂等锁沿用入库约定，但刷新命令使用独立命名空间。
        jdbc.queryForObject(
                "SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
                Object.class,
                "refresh/" + actor.tenantId() + "/" + actor.userId() + "/" + request.requestId());
        requireUrl(actor, document);
        // replay：根据幂等键查到的历史执行结果。
        var replay =
                jdbc.queryForList(
                        "SELECT * FROM kb_refresh_run WHERE tenant_id=? AND owner_id=? AND request_id=?",
                        actor.tenantId(),
                        actor.userId(),
                        request.requestId());
        if (!replay.isEmpty()) {
            // row：当前数据库记录或表格数据行。
            var row = replay.get(0);
            if (!document.equals(row.get("document_id"))
                    || request.confirmOverwriteEdited()
                            != Boolean.TRUE.equals(row.get("confirm_overwrite_edited"))
                    || !Objects.equals(
                            request.expectedDocumentVersion(),
                            row.get("expected_document_version")))
                throw conflict(
                        "KB_IDEMPOTENCY_CONFLICT", "requestId is bound to another refresh request");
            return new RefreshSubmittedBO(runView((UUID) row.get("id")), true);
        }
        // doc：当前操作涉及的文档。
        var doc = lock(actor, document);
        downloader.validateUrl(doc.getSourceUrl());
        if (!doc.getEnabled())
            throw conflict("KB_DOCUMENT_DISABLED", "Enable the document before refreshing");
        if (activeRefresh(document))
            throw conflict("KB_REFRESH_ACTIVE", "Document already has an active refresh");
        if (activeJob(document))
            throw conflict("KB_JOB_ACTIVE", "Wait for ingestion before refreshing");
        checkManual(doc, request.confirmOverwriteEdited(), request.expectedDocumentVersion());
        // id：当前业务对象的唯一标识。
        UUID id =
                create(
                        doc,
                        request.requestId(),
                        "MANUAL",
                        request.confirmOverwriteEdited(),
                        request.expectedDocumentVersion());
        return new RefreshSubmittedBO(runView(id), false);
    }

    /**
     * 在文档归属范围内分页读取刷新历史。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param document 本次操作涉及的文档唯一标识。
     * @param current 当前分页页码，从一开始。
     * @param size 分页容量或当前数据的字节数。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 10)
    public PageResponse<RefreshRunBO> list(
            KnowledgeBasePrincipal actor, UUID document, int current, int size) {
        requireUrl(actor, document);
        // total：满足当前查询条件的记录总数。
        long total =
                jdbc.queryForObject(
                        "SELECT count(*) FROM kb_refresh_run WHERE document_id=?",
                        Long.class,
                        document);
        return new PageResponse<>(
                current,
                size,
                total,
                jdbc.query(
                        "SELECT * FROM kb_refresh_run WHERE document_id=? ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
                        RefreshServiceImpl::runView,
                        document,
                        size,
                        ((long) current - 1) * size));
    }

    /** 在统一业务锁下将到期计划转为持久化运行，并跳过当前不可刷新的文档。 */
    public void enqueueDue() {
        tx.executeWithoutResult(
                status -> {
                    // status：本次短事务的执行状态，由事务模板传入。
                    // c：当前待领取或待收敛的持久化任务记录。
                    for (var c :
                            jdbc.queryForList(
                                    "SELECT s.document_id,d.kb_id FROM kb_refresh_schedule s JOIN kb_document d ON d.id=s.document_id WHERE s.enabled AND s.next_run_at<=clock_timestamp() ORDER BY s.next_run_at,s.document_id LIMIT 32")) {
                        if (!tryLocks((UUID) c.get("kb_id"), (UUID) c.get("document_id"))) continue;
                        // document：本次操作涉及的文档唯一标识。
                        UUID document = (UUID) c.get("document_id");
                        // schedules：当前查询命中的刷新计划记录集合。
                        var schedules =
                                jdbc.queryForList(
                                        "SELECT * FROM kb_refresh_schedule WHERE document_id=? AND enabled AND next_run_at<=clock_timestamp() FOR UPDATE SKIP LOCKED",
                                        document);
                        if (schedules.isEmpty() || activeRefresh(document)) continue;
                        // doc：当前操作涉及的文档。
                        var doc = rawDocument(document);
                        // id：当前业务对象的唯一标识。
                        UUID id =
                                create(
                                        doc,
                                        "scheduled-" + UUID.randomUUID(),
                                        "SCHEDULED",
                                        false,
                                        null);
                        // run：当前刷新运行记录或运行标识。
                        var run = jdbc.queryForMap("SELECT * FROM kb_refresh_run WHERE id=?", id);
                        if (doc.getDeletedAt() != null || !doc.getEnabled()) {
                            jdbc.update(
                                    "UPDATE kb_refresh_schedule SET enabled=FALSE WHERE document_id=?",
                                    document);
                            state.finish(run, "SKIPPED", "DOCUMENT_DISABLED_OR_DELETED");
                        } else if (activeJob(document))
                            state.finish(run, "SKIPPED", "INGESTION_ACTIVE");
                        else if (Boolean.TRUE.equals(doc.getHasManualChanges()))
                            state.finish(run, "SKIPPED", "MANUAL_CHANGES_REQUIRE_CONFIRMATION");
                    }
                });
    }

    /**
     * 按本实例并发限制领取到期刷新，处理失效租约并生成新的栅栏令牌。
     *
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public RefreshClaimBO claimNext() {
        return tx.execute(
                status -> {
                    // status：本次短事务的执行状态，由事务模板传入。
                    if (jdbc.queryForObject(
                                    "SELECT count(*) FROM kb_refresh_run WHERE lease_owner=? AND status IN ('RUNNING','WAITING_INGESTION') AND lease_until>clock_timestamp()",
                                    Integer.class,
                                    instance)
                            >= config.concurrency()) return null;
                    // candidates：本次待领取的任务、可选解析策略或召回候选集合。
                    var candidates =
                            jdbc.queryForList(
                                    "SELECT id,document_id,kb_id FROM kb_refresh_run WHERE (status='PENDING' AND next_run_at<=clock_timestamp()) OR (status IN ('RUNNING','WAITING_INGESTION') AND lease_until<=clock_timestamp()) ORDER BY COALESCE(next_run_at,lease_until),created_at,id LIMIT 32");
                    // c：当前待领取或待收敛的持久化任务记录。
                    for (var c : candidates) {
                        // document：本次操作涉及的文档唯一标识。
                        UUID document = (UUID) c.get("document_id");
                        if (!tryLocks((UUID) c.get("kb_id"), document)) continue;
                        // rows：数据库结果或当前表格数据行集合。
                        var rows =
                                jdbc.queryForList(
                                        "SELECT * FROM kb_refresh_run WHERE id=? AND ((status='PENDING' AND next_run_at<=clock_timestamp()) OR (status IN ('RUNNING','WAITING_INGESTION') AND lease_until<=clock_timestamp())) FOR UPDATE SKIP LOCKED",
                                        c.get("id"));
                        if (rows.isEmpty()) continue;
                        // run：当前刷新运行记录或运行标识。
                        var run = rows.get(0);
                        // doc：当前操作涉及的文档。
                        var doc = rawDocument(document);
                        if (!inputsValid(run, doc)) {
                            cancelJob(run, doc);
                            state.finish(run, "SKIPPED", "INPUT_CHANGED");
                            continue;
                        }
                        if (!"PENDING".equals(run.get("status"))) {
                            cancelJob(run, doc);
                            retryOrFinish(run, "KB_REFRESH_LEASE_EXPIRED", true);
                            continue;
                        }
                        if (activeJob(document)) {
                            state.finish(run, "SKIPPED", "INGESTION_ACTIVE");
                            continue;
                        }
                        if (((Number) run.get("attempts")).intValue() >= config.maxAttempts()) {
                            state.finish(run, "FAILED", "KB_REFRESH_ATTEMPTS_EXHAUSTED");
                            continue;
                        }
                        // token：当前任务的租约栅栏令牌或清理凭据。
                        long token =
                                jdbc.queryForObject(
                                        "UPDATE kb_refresh_schedule SET claim_token=claim_token+1,lease_owner=?,lease_until=clock_timestamp()+(?*interval '1 millisecond'),next_run_at=NULL WHERE document_id=? RETURNING claim_token",
                                        Long.class,
                                        instance,
                                        config.lease().toMillis(),
                                        document);
                        jdbc.update(
                                "UPDATE kb_refresh_run SET status='RUNNING',attempts=attempts+1,claim_token=?,lease_owner=?,lease_until=clock_timestamp()+(?*interval '1 millisecond'),deadline=COALESCE(deadline,clock_timestamp()+(?*interval '1 millisecond')),started_at=COALESCE(started_at,clock_timestamp()),execution_started_at=NULL,next_run_at=NULL WHERE id=?",
                                token,
                                instance,
                                config.lease().toMillis(),
                                config.runDeadline().toMillis(),
                                run.get("id"));
                        return new RefreshClaimBO(
                                (UUID) run.get("id"),
                                document,
                                (UUID) run.get("kb_id"),
                                token,
                                instance);
                    }
                    return null;
                });
    }

    /**
     * 校验一次性执行权，条件下载源文件，登记候选对象并提交后续入库任务。
     *
     * @param claim 当前后台任务的租约凭据或领取记录。
     */
    public void execute(RefreshClaimBO claim) {
        // 第一阶段：以租约令牌取得一次性进入权，重复投递直接结束。
        // entered：是否成功取得该执行凭据的一次性流水线进入权。
        // 同一凭据重复投递时不得改变正常执行或其候选对象。
        boolean entered =
                Boolean.TRUE.equals(
                        tx.execute(
                                status -> {
                                    // status：本次短事务的执行状态，由事务模板传入。
                                    lockRaw(claim);
                                    return jdbc.update(
                                                    "UPDATE kb_refresh_run SET execution_started_at=clock_timestamp() WHERE id=? AND claim_token=? AND lease_owner=? AND lease_until>clock_timestamp() AND deadline>clock_timestamp() AND status='RUNNING' AND execution_started_at IS NULL",
                                                    claim.id(),
                                                    claim.token(),
                                                    claim.owner())
                                            == 1;
                                }));
        if (!entered) return;
        try {
            // 第二阶段：在短事务中固定源文件及条件请求基线，网络下载放在事务之外。
            // input：本阶段需要处理的输入内容或固定输入快照。
            var input =
                    tx.execute(
                            status -> {
                                // status：本次短事务的执行状态，由事务模板传入。
                                lockRaw(claim);
                                // run：当前刷新运行记录或运行标识。
                                var run =
                                        state.requireClaim(
                                                claim.id(), claim.token(), claim.owner());
                                // result：当前步骤产生的处理结果。
                                var result = new HashMap<>(run);
                                result.putAll(
                                        jdbc.queryForMap(
                                                "SELECT source_url,original_filename,content_hash,active_generation,ingestion_spec FROM kb_document WHERE id=?",
                                                claim.documentId()));
                                result.putAll(
                                        jdbc.queryForMap(
                                                "SELECT last_content_hash,last_etag,last_modified FROM kb_refresh_schedule WHERE document_id=?",
                                                claim.documentId()));
                                return result;
                            });
            // 只有当前已发布内容与历史成功摘要一致，才允许发送条件请求标识。
            // conditional：当前内容是否已有可安全使用的条件请求基线。
            boolean conditional =
                    input.get("active_generation") != null
                            && Objects.equals(
                                    input.get("content_hash"), input.get("last_content_hash"));
            // download：当前持有的远程下载结果及临时文件。
            try (var download =
                    downloader.download(
                            (String) input.get("source_url"),
                            conditional ? (String) input.get("last_etag") : null,
                            conditional ? (String) input.get("last_modified") : null,
                            instant(input.get("deadline")))) {
                // 第三阶段：304 或相同内容摘要直接结束刷新，保留当前代次。
                // same：下载内容是否与当前已发布源内容相同。
                boolean same =
                        download.httpStatus() == 304
                                || input.get("active_generation") != null
                                        && download.hash().equals(input.get("content_hash"));
                if (same) {
                    tx.executeWithoutResult(
                            status -> {
                                // status：本次短事务的执行状态，由事务模板传入。
                                lockRaw(claim);
                                // run：当前刷新运行记录或运行标识。
                                var run =
                                        state.requireClaim(
                                                claim.id(), claim.token(), claim.owner());
                                jdbc.update(
                                        "UPDATE kb_refresh_run SET http_status=? WHERE id=?",
                                        download.httpStatus(),
                                        claim.id());
                                // 相同字节已经属于当前已发布版本，可安全更新条件请求标识并跳过嵌入。
                                jdbc.update(
                                        "UPDATE kb_refresh_schedule SET last_content_hash=?,last_etag=?,last_modified=? WHERE document_id=?",
                                        input.get("content_hash"),
                                        download.httpStatus() == 304 && download.etag() == null
                                                ? input.get("last_etag")
                                                : download.etag(),
                                        download.httpStatus() == 304
                                                        && download.lastModified() == null
                                                ? input.get("last_modified")
                                                : download.lastModified(),
                                        claim.documentId());
                                state.finish(
                                        run,
                                        "SKIPPED",
                                        download.httpStatus() == 304
                                                ? "HTTP_NOT_MODIFIED"
                                                : "CONTENT_HASH_UNCHANGED");
                            });
                    return;
                }
                // 第四阶段：内容变化时复用上传校验，先持久化候选对象再上传。
                // prepared：完成大小、摘要及真实格式校验的临时上传文件。
                try (var prepared =
                        PreparedUpload.prepare(
                                download.file((String) input.get("original_filename")),
                                storageConfig)) {
                    // spec：文档入库配置或当前切片策略参数。
                    var spec =
                            json.readValue(
                                    input.get("ingestion_spec").toString(), IngestionSpecBO.class);
                    parsers.resolve(
                            prepared.mime, spec.parser(), prepared.filename, spec.parseProfile());
                    // key：对象存储键、配置键或业务幂等键。
                    String key =
                            "kb/" + claim.kbId() + "/remote/" + UUID.randomUUID() + "/original";
                    tx.executeWithoutResult(
                            status -> {
                                // status：本次短事务的执行状态，由事务模板传入。
                                lockRaw(claim);
                                // run：当前刷新运行记录或运行标识。
                                var run =
                                        state.requireClaim(
                                                claim.id(), claim.token(), claim.owner());
                                state.reserveObject(run, key);
                                jdbc.update(
                                        "UPDATE kb_refresh_run SET candidate_filename=?,candidate_mime=?,candidate_size=?,candidate_hash=?,candidate_etag=?,candidate_last_modified=?,http_status=? WHERE id=?",
                                        prepared.filename,
                                        prepared.mime,
                                        prepared.size,
                                        prepared.hash,
                                        download.etag(),
                                        download.lastModified(),
                                        download.httpStatus(),
                                        claim.id());
                            });
                    // 第五阶段：写入候选对象并提交标准入库任务，源文件切换由成功发布事务完成。
                    storage.put(key, prepared.path, prepared.mime, prepared.hash);
                    tx.executeWithoutResult(
                            status -> {
                                // status：本次短事务的执行状态，由事务模板传入。
                                lockRaw(claim);
                                // run：当前刷新运行记录或运行标识。
                                var run =
                                        state.requireClaim(
                                                claim.id(), claim.token(), claim.owner());
                                jobs.submitRefresh(actor(run), run, claim.token());
                            });
                }
            }
        } catch (Exception error) {
            // error：本次处理捕获的异常，用于归类失败原因。
            // 工作线程中断后保留持久化租约，由租约恢复流程接管。
            if (Thread.currentThread().isInterrupted()) return;
            tx.executeWithoutResult(
                    status -> {
                        // status：本次短事务的执行状态，由事务模板传入。
                        lockRaw(claim);
                        // rows：数据库结果或当前表格数据行集合。
                        var rows =
                                jdbc.queryForList(
                                        "SELECT * FROM kb_refresh_run WHERE id=? AND claim_token=? AND lease_owner=? AND lease_until>clock_timestamp() AND status='RUNNING' FOR UPDATE",
                                        claim.id(),
                                        claim.token(),
                                        claim.owner());
                        if (rows.isEmpty()) return;
                        // kb：本次捕获或匹配的异常，用于保留业务分类及失败原因。
                        // code：对外使用的稳定业务错误分类。
                        String code =
                                error instanceof KnowledgeBaseException kb
                                        ? kb.code()
                                        : "KB_REFRESH_FAILED";
                        retryOrFinish(
                                rows.get(0),
                                code,
                                Set.of(
                                                "KB_REMOTE_TIMEOUT",
                                                "KB_REMOTE_BUSY",
                                                "KB_REMOTE_RATE_LIMITED",
                                                "KB_REMOTE_DOWNLOAD_FAILED",
                                                "KB_REMOTE_HTTP_ERROR",
                                                "KB_STORAGE_UNAVAILABLE")
                                        .contains(code));
                    });
        }
    }

    /** 只延长仍有效的刷新租约，不恢复过期租约且不超过运行截止时间。 */
    public void heartbeat() {
        tx.executeWithoutResult(
                status -> {
                    // status：本次短事务的执行状态，由事务模板传入。
                    // c：当前待领取或待收敛的持久化任务记录。
                    for (var c :
                            jdbc.queryForList(
                                    "SELECT id,kb_id,document_id FROM kb_refresh_run WHERE lease_owner=? AND status IN ('RUNNING','WAITING_INGESTION') ORDER BY id LIMIT 32",
                                    instance)) {
                        if (!tryLocks((UUID) c.get("kb_id"), (UUID) c.get("document_id"))) continue;
                        jdbc.update(
                                """
                    UPDATE kb_refresh_schedule s SET lease_until=LEAST(r.deadline,clock_timestamp()+(?*interval '1 millisecond')) FROM kb_refresh_run r
                    WHERE r.id=? AND r.document_id=s.document_id AND r.lease_owner=? AND s.lease_owner=r.lease_owner AND s.claim_token=r.claim_token
                    AND r.status IN ('RUNNING','WAITING_INGESTION') AND r.lease_until>clock_timestamp() AND s.lease_until>clock_timestamp() AND r.deadline>clock_timestamp()
                    """,
                                config.lease().toMillis(),
                                c.get("id"),
                                instance);
                        jdbc.update(
                                "UPDATE kb_refresh_run r SET lease_until=s.lease_until FROM kb_refresh_schedule s WHERE r.id=? AND s.document_id=r.document_id AND r.lease_owner=? AND s.lease_owner=r.lease_owner AND s.claim_token=r.claim_token AND r.status IN ('RUNNING','WAITING_INGESTION') AND r.lease_until>clock_timestamp() AND r.deadline>clock_timestamp()",
                                c.get("id"),
                                instance);
                    }
                });
    }

    /** 收敛输入已变化、预算耗尽或下游入库失败的刷新运行。 */
    public void reconcile() {
        tx.executeWithoutResult(
                status -> {
                    // status：本次短事务的执行状态，由事务模板传入。
                    // c：当前待领取或待收敛的持久化任务记录。
                    for (var c :
                            jdbc.queryForList(
                                    "SELECT id,document_id,kb_id FROM kb_refresh_run WHERE status IN ('RUNNING','WAITING_INGESTION') ORDER BY started_at,id LIMIT 64")) {
                        if (!tryLocks((UUID) c.get("kb_id"), (UUID) c.get("document_id"))) continue;
                        // rows：数据库结果或当前表格数据行集合。
                        var rows =
                                jdbc.queryForList(
                                        "SELECT * FROM kb_refresh_run WHERE id=? AND status IN ('RUNNING','WAITING_INGESTION') FOR UPDATE",
                                        c.get("id"));
                        if (rows.isEmpty()) continue;
                        // run：当前刷新运行记录或运行标识。
                        var run = rows.get(0);
                        // doc：当前操作涉及的文档。
                        var doc = rawDocument((UUID) c.get("document_id"));
                        if (!inputsValid(run, doc)) {
                            cancelJob(run, doc);
                            state.finish(run, "SKIPPED", "INPUT_CHANGED");
                            continue;
                        }
                        if (instant(run.get("deadline")).isBefore(state.now().toInstant())) {
                            cancelJob(run, doc);
                            state.finish(run, "FAILED", "KB_REFRESH_DEADLINE");
                            continue;
                        }
                        if (run.get("job_id") != null) {
                            // job：当前入库任务记录或任务标识。
                            var job =
                                    jdbc.queryForMap(
                                            "SELECT status,error_code FROM kb_ingestion_job WHERE id=?",
                                            run.get("job_id"));
                            if (Set.of("FAILED", "CANCELLED").contains(job.get("status")))
                                state.finish(
                                        run,
                                        "FAILED",
                                        Objects.toString(
                                                job.get("error_code"), "KB_INGESTION_FAILED"));
                        }
                    }
                    jdbc.update(
                            "UPDATE kb_url_import SET status='FAILED',error_code='KB_IMPORT_EXPIRED' WHERE status='RUNNING' AND lease_until<=clock_timestamp()");
                });
    }

    /**
     * 按输入版本、错误类型及尝试次数决定退避重试或进入终态。
     *
     * @param run 当前刷新运行记录或运行标识。
     * @param reason 本次跳过、失败或状态迁移的原因分类。
     * @param retryable 该错误是否允许在预算和次数限制内重试。
     */
    private void retryOrFinish(Map<String, Object> run, String reason, boolean retryable) {
        // doc：当前操作涉及的文档。
        var doc = rawDocument((UUID) run.get("document_id"));
        if (!inputsValid(run, doc)) {
            state.finish(run, "SKIPPED", "INPUT_CHANGED");
            return;
        }
        if (!retryable
                || ((Number) run.get("attempts")).intValue()
                        >= ((Number) run.get("max_attempts")).intValue()
                || run.get("deadline") != null
                        && !instant(run.get("deadline")).isAfter(state.now().toInstant())) {
            state.finish(run, "FAILED", reason);
            return;
        }
        state.queueObject(
                doc.getId(),
                doc.getKbId(),
                (String) run.get("candidate_object_key"),
                (UUID) run.get("id"));
        jdbc.update(
                "UPDATE kb_refresh_run SET status='PENDING',reason=?,next_run_at=clock_timestamp()+(?*interval '1 second'),lease_owner=NULL,lease_until=NULL,job_id=NULL,candidate_object_key=NULL WHERE id=?",
                reason,
                Math.min(60, 2L << Math.max(0, ((Number) run.get("attempts")).intValue() - 1)),
                run.get("id"));
        jdbc.update(
                "UPDATE kb_refresh_schedule SET claim_token=claim_token+1,lease_owner=NULL,lease_until=NULL WHERE document_id=?",
                run.get("document_id"));
    }

    /**
     * 固定文档及计划版本，创建待执行刷新运行并暂停计划的下次触发。
     *
     * @param doc 本次操作涉及的文档实体。
     * @param key 对象存储键、配置键或业务幂等键。
     * @param trigger 触发本次刷新的来源分类。
     * @param confirm 是否确认覆盖人工维护内容。
     * @param expected 调用方要求匹配的文档版本。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private UUID create(
            DocumentDO doc, String key, String trigger, boolean confirm, Long expected) {
        state.ensure(doc.getId());
        // scheduleVersion：刷新提交时固定的计划配置版本。
        long scheduleVersion =
                jdbc.queryForObject(
                        "SELECT version FROM kb_refresh_schedule WHERE document_id=? FOR UPDATE",
                        Long.class,
                        doc.getId());
        // id：当前业务对象的唯一标识。
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO kb_refresh_run(id,document_id,kb_id,tenant_id,owner_id,request_id,trigger_type,status,document_version,config_revision,schedule_version,confirm_overwrite_edited,expected_document_version,max_attempts) VALUES (?,?,?,?,?,?,?,'PENDING',?,?,?,?,?,?)",
                id,
                doc.getId(),
                doc.getKbId(),
                doc.getTenantId(),
                doc.getOwnerId(),
                key,
                trigger,
                doc.getVersion(),
                doc.getConfigRevision(),
                scheduleVersion,
                confirm,
                expected,
                config.maxAttempts());
        jdbc.update(
                "UPDATE kb_refresh_schedule SET next_run_at=NULL WHERE document_id=?", doc.getId());
        return id;
    }

    /**
     * 校验文档归属并要求其来源类型为 URL。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param id 当前业务对象的唯一标识。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private DocumentDO requireUrl(KnowledgeBasePrincipal actor, UUID id) {
        // doc：当前操作涉及的文档。
        var doc = documents.requireOwned(actor, id);
        if (!"URL".equals(doc.getSourceType()))
            throw conflict(
                    "KB_NOT_REMOTE_DOCUMENT", "Only URL-imported documents can be refreshed");
        return doc;
    }

    /**
     * 按知识库、文档顺序取得归属锁并确保刷新计划存在。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param id 当前业务对象的唯一标识。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private DocumentDO lock(KnowledgeBasePrincipal actor, UUID id) {
        // doc：当前操作涉及的文档。
        var doc = requireUrl(actor, id);
        bases.lockOwned(actor, doc.getKbId());
        doc = documents.lockOwned(actor, id);
        state.ensure(id);
        return doc;
    }

    /**
     * 按统一顺序锁定领取凭据对应的知识库和文档。
     *
     * @param claim 当前后台任务的租约凭据或领取记录。
     */
    private void lockRaw(RefreshClaimBO claim) {
        jdbc.queryForObject(
                "SELECT id FROM kb_knowledge_base WHERE id=? FOR UPDATE", UUID.class, claim.kbId());
        jdbc.queryForObject(
                "SELECT id FROM kb_document WHERE id=? FOR UPDATE", UUID.class, claim.documentId());
    }

    /**
     * 尝试非阻塞地锁定知识库和文档，避免调度任务相互等待。
     *
     * @param kb 本次操作涉及的知识库唯一标识。
     * @param doc 本次操作涉及的文档唯一标识。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private boolean tryLocks(UUID kb, UUID doc) {
        return !jdbc.queryForList(
                                "SELECT id FROM kb_knowledge_base WHERE id=? FOR UPDATE SKIP LOCKED",
                                UUID.class,
                                kb)
                        .isEmpty()
                && !jdbc.queryForList(
                                "SELECT id FROM kb_document WHERE id=? FOR UPDATE SKIP LOCKED",
                                UUID.class,
                                doc)
                        .isEmpty();
    }

    /**
     * 读取用于刷新状态校验的文档字段。
     *
     * @param id 当前业务对象的唯一标识。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private DocumentDO rawDocument(UUID id) {
        return jdbc.queryForObject(
                "SELECT * FROM kb_document WHERE id=?",
                (rs, n) -> {
                    // rs：数据库查询的当前结果行。
                    // n：当前结果行的顺序编号，由 JDBC 行映射器传入。
                    // d：当前文档记录。
                    var d = new DocumentDO();
                    d.setId(id);
                    d.setKbId(rs.getObject("kb_id", UUID.class));
                    d.setTenantId(rs.getString("tenant_id"));
                    d.setOwnerId(rs.getString("owner_id"));
                    d.setEnabled(rs.getBoolean("enabled"));
                    d.setDeletedAt(rs.getObject("deleted_at", OffsetDateTime.class));
                    d.setVersion(rs.getLong("version"));
                    d.setConfigRevision(rs.getLong("config_revision"));
                    d.setHasManualChanges(rs.getBoolean("has_manual_changes"));
                    return d;
                },
                id);
    }

    /**
     * 复核文档启用状态、文档版本、处理配置及刷新计划版本。
     *
     * @param run 当前刷新运行记录或运行标识。
     * @param doc 本次操作涉及的文档实体。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private boolean inputsValid(Map<String, Object> run, DocumentDO doc) {
        return doc.getDeletedAt() == null
                && doc.getEnabled()
                && doc.getVersion().equals(run.get("document_version"))
                && doc.getConfigRevision().equals(run.get("config_revision"))
                && Objects.equals(
                        run.get("schedule_version"),
                        jdbc.queryForObject(
                                "SELECT version FROM kb_refresh_schedule WHERE document_id=?",
                                Long.class,
                                doc.getId()));
    }

    /**
     * 取消当前刷新关联且仍可访问的入库任务。
     *
     * @param run 当前刷新运行记录或运行标识。
     * @param doc 本次操作涉及的文档实体。
     */
    private void cancelJob(Map<String, Object> run, DocumentDO doc) {
        if (run.get("job_id") != null && doc.getDeletedAt() == null)
            jobs.cancel(actor(run), (UUID) run.get("job_id"));
    }

    /**
     * 取消指定文档活动刷新运行所关联的入库任务。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param id 当前业务对象的唯一标识。
     */
    private void cancelRunJobs(KnowledgeBasePrincipal actor, UUID id) {
        // job：当前入库任务记录或任务标识。
        for (var job :
                jdbc.queryForList(
                        "SELECT job_id FROM kb_refresh_run WHERE document_id=? AND status IN ('PENDING','RUNNING','WAITING_INGESTION') AND job_id IS NOT NULL",
                        UUID.class,
                        id)) jobs.cancel(actor, job);
    }

    /**
     * 判断文档是否已有尚未结束的刷新运行。
     *
     * @param doc 本次操作涉及的文档唯一标识。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private boolean activeRefresh(UUID doc) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM kb_refresh_run WHERE document_id=? AND status IN ('PENDING','RUNNING','WAITING_INGESTION'))",
                        Boolean.class,
                        doc));
    }

    /**
     * 判断文档是否已有待执行或运行中的入库任务。
     *
     * @param doc 本次操作涉及的文档唯一标识。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private boolean activeJob(UUID doc) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM kb_ingestion_job WHERE document_id=? AND status IN ('PENDING','RUNNING'))",
                        Boolean.class,
                        doc));
    }

    /**
     * 要求覆盖人工内容时显式确认，并校验调用方审阅的文档版本。
     *
     * @param doc 本次操作涉及的文档实体。
     * @param confirm 是否确认覆盖人工维护内容。
     * @param expected 调用方要求匹配的文档版本。
     */
    private static void checkManual(DocumentDO doc, boolean confirm, Long expected) {
        if (Boolean.TRUE.equals(doc.getHasManualChanges()) && (!confirm || expected == null))
            throw conflict(
                    "KB_MANUAL_OVERWRITE_REQUIRED",
                    "Refresh replaces manual content and chunk enabled settings; confirmOverwriteEdited=true and expectedDocumentVersion are required after review");
        if (expected != null && !expected.equals(doc.getVersion()))
            throw conflict("KB_VERSION_CONFLICT", "Reload the current document version");
    }

    /**
     * 读取刷新计划及最近一次运行摘要。
     *
     * @param doc 本次操作涉及的文档唯一标识。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private RefreshScheduleResultBO scheduleView(UUID doc) {
        // rs：数据库查询的当前结果行。
        // n：当前结果行的顺序编号，由 JDBC 行映射器传入。
        return jdbc.queryForObject(
                "SELECT * FROM kb_refresh_schedule WHERE document_id=?",
                (rs, n) ->
                        new RefreshScheduleResultBO(
                                doc,
                                rs.getBoolean("enabled"),
                                rs.getString("cron_expression"),
                                rs.getString("timezone"),
                                rs.getLong("version"),
                                rs.getObject("next_run_at", OffsetDateTime.class),
                                rs.getString("last_status"),
                                rs.getString("last_reason"),
                                rs.getObject("last_run_id", UUID.class),
                                rs.getString("last_content_hash"),
                                rs.getInt("consecutive_failures")),
                doc);
    }

    /**
     * 读取或转换单次刷新运行的查询响应。
     *
     * @param id 当前业务对象的唯一标识。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private RefreshRunBO runView(UUID id) {
        return jdbc.queryForObject(
                "SELECT * FROM kb_refresh_run WHERE id=?", RefreshServiceImpl::runView, id);
    }

    /**
     * 读取或转换单次刷新运行的查询响应。
     *
     * @param rs 数据库查询的当前结果行。
     * @param n 当前结果行的顺序编号，由 JDBC 行映射器传入。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private static RefreshRunBO runView(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new RefreshRunBO(
                rs.getObject("id", UUID.class),
                rs.getObject("document_id", UUID.class),
                rs.getString("request_id"),
                rs.getString("trigger_type"),
                rs.getString("status"),
                rs.getObject("job_id", UUID.class),
                rs.getInt("attempts"),
                rs.getInt("max_attempts"),
                rs.getObject("http_status", Integer.class),
                rs.getString("reason"),
                rs.getString("error_message"),
                rs.getObject("next_run_at", OffsetDateTime.class),
                rs.getObject("started_at", OffsetDateTime.class),
                rs.getObject("completed_at", OffsetDateTime.class),
                rs.getObject("duration_ms", Long.class));
    }

    /**
     * 从持久化运行记录恢复租户与所有者身份，仅供内部业务校验。
     *
     * @param row 当前数据库记录或表格数据行。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    static KnowledgeBasePrincipal actor(Map<String, Object> row) {
        return new KnowledgeBasePrincipal(
                "persisted-refresh",
                "unused",
                (String) row.get("tenant_id"),
                (String) row.get("owner_id"));
    }

    /**
     * 将数据库时间值统一转换为绝对时间。
     *
     * @param value 当前待校验、转换或写入的值。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    static Instant instant(Object value) {
        // offset：分页偏移量或数据库时间的带偏移表示。
        return value instanceof OffsetDateTime offset
                ? offset.toInstant()
                : ((java.sql.Timestamp) value).toInstant();
    }

    /**
     * 创建并发版本或业务状态冲突异常。
     *
     * @param code 对外使用的稳定业务错误分类。
     * @param message 对外展示的业务错误或状态说明。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private static KnowledgeBaseException conflict(String code, String message) {
        return new KnowledgeBaseException(409, code, message);
    }
}
