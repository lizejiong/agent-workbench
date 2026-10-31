package com.lzj.rag.backend.chunk.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lzj.rag.backend.chunk.config.MaintenanceProperties;
import com.lzj.rag.backend.chunk.dao.entity.ChunkDO;
import com.lzj.rag.backend.chunk.dao.mapper.StoredChunkMapper;
import com.lzj.rag.backend.chunk.service.ChunkMaintenanceService;
import com.lzj.rag.backend.chunk.service.bo.ChunkChangedBO;
import com.lzj.rag.backend.chunk.service.bo.ChunkContentBO;
import com.lzj.rag.backend.chunk.service.bo.ChunkViewBO;
import com.lzj.rag.backend.document.dao.entity.DocumentDO;
import com.lzj.rag.backend.document.dao.mapper.DocumentMapper;
import com.lzj.rag.backend.document.service.bo.DocumentBO;
import com.lzj.rag.backend.document.service.internal.DocumentAccess;
import com.lzj.rag.backend.ingestion.config.IngestionProperties;
import com.lzj.rag.backend.ingestion.service.IngestionJobService;
import com.lzj.rag.backend.knowledgebase.dao.entity.KnowledgeBaseDO;
import com.lzj.rag.backend.knowledgebase.exception.KnowledgeBaseException;
import com.lzj.rag.backend.knowledgebase.service.internal.KnowledgeBaseAccess;
import com.lzj.rag.backend.model.catalog.EmbeddingCatalog;
import com.lzj.rag.backend.model.service.EmbeddingClient;
import com.lzj.rag.backend.model.service.bo.EmbeddingBindingBO;
import com.lzj.rag.backend.model.service.bo.EmbeddingResultBO;
import com.lzj.rag.backend.refresh.state.RefreshState;
import com.lzj.rag.backend.search.repository.vector.VectorStore;
import com.lzj.rag.backend.search.repository.vector.dto.VectorRecordDTO;
import com.lzj.rag.backend.search.repository.vector.dto.VectorScopeDTO;
import com.lzj.rag.backend.security.model.KnowledgeBasePrincipal;
import com.lzj.rag.framework.convention.page.PageResponse;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.*;
import java.util.*;

/** 维护当前有效代次的内容与向量，通过文档版本校验防止覆盖并发修改。 */
@Service
@EnableConfigurationProperties(MaintenanceProperties.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class ChunkMaintenanceServiceImpl implements ChunkMaintenanceService {

    /** 供内部流程校验文档归属和取得行锁的访问契约。 */
    private final DocumentAccess documents;

    /** 文档实体数据访问接口。 */
    private final DocumentMapper documentMapper;

    /** 切片实体数据访问接口。 */
    private final StoredChunkMapper chunks;

    /** 供内部流程校验知识库归属和取得行锁的访问契约。 */
    private final KnowledgeBaseAccess bases;

    /** 负责持久化入库任务及租约状态迁移的业务服务。 */
    private final IngestionJobService jobs;

    /** 执行数据库查询及受条件保护的业务状态更新。 */
    private final JdbcTemplate jdbc;

    /** 受固定模型身份约束的嵌入客户端。 */
    private final EmbeddingClient embeddings;

    /** 在固定所有权及命名空间内读写切片向量的存储组件。 */
    private final VectorStore vectors;

    /** 用于业务请求、持久化快照与元数据转换的 JSON 映射器。 */
    private final ObjectMapper json;

    /** 人工维护及后台清理的容量、租约和重试限制。 */
    private final MaintenanceProperties config;

    /** 入库流水线的时间预算、文本长度、切片数量和向量写入限制。 */
    private final IngestionProperties ingestion;

    /** 用于短时状态变更的事务模板。 */
    private final TransactionTemplate tx;

    /** 负责刷新凭据、源对象切换及终态维护的组件。 */
    private final RefreshState refresh;

    /**
     * 初始化当前组件，保存业务依赖及本次使用的配置。
     *
     * @param documents 供内部流程校验文档归属和取得行锁的访问契约。
     * @param documentMapper 文档实体数据访问接口。
     * @param chunks 切片实体数据访问接口。
     * @param bases 供内部流程校验知识库归属和取得行锁的访问契约。
     * @param jobs 负责持久化入库任务及租约状态迁移的业务服务。
     * @param jdbc 执行数据库查询及受条件保护的业务状态更新。
     * @param embeddings 受固定模型身份约束的嵌入客户端。
     * @param vectors 在固定所有权及命名空间内读写切片向量的存储组件。
     * @param json 用于业务请求、持久化快照与元数据转换的 JSON 映射器。
     * @param config 人工维护及后台清理的容量、租约和重试限制。
     * @param ingestion 入库流水线的时间预算、文本长度、切片数量和向量写入限制。
     * @param transactions 创建后台短事务及只读事务的事务管理器。
     * @param refresh 负责刷新凭据、源对象切换及终态维护的组件。
     */
    public ChunkMaintenanceServiceImpl(
            DocumentAccess documents,
            DocumentMapper documentMapper,
            StoredChunkMapper chunks,
            KnowledgeBaseAccess bases,
            IngestionJobService jobs,
            JdbcTemplate jdbc,
            EmbeddingClient embeddings,
            VectorStore vectors,
            ObjectMapper json,
            MaintenanceProperties config,
            IngestionProperties ingestion,
            PlatformTransactionManager transactions,
            RefreshState refresh) {
        this.documents = documents;
        this.documentMapper = documentMapper;
        this.chunks = chunks;
        this.bases = bases;
        this.jobs = jobs;
        this.jdbc = jdbc;
        this.embeddings = embeddings;
        this.vectors = vectors;
        this.json = json;
        this.config = config;
        this.ingestion = ingestion;
        tx = new TransactionTemplate(transactions);
        tx.setTimeout(30);
        this.refresh = refresh;
    }

    /**
     * 在当前有效代次内按启用状态分页查询切片。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param id 当前业务对象的唯一标识。
     * @param current 当前分页页码，从一开始。
     * @param size 分页容量或当前数据的字节数。
     * @param enabled 是否启用该资源或能力。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 10)
    public PageResponse<ChunkViewBO> list(
            KnowledgeBasePrincipal actor, UUID id, int current, int size, Boolean enabled) {
        // doc：当前操作涉及的文档。
        var doc = documents.requireOwned(actor, id);
        // filter：包含租户、所有者及当前业务筛选条件的查询约束。
        var filter = filter(actor, doc);
        if (enabled != null) filter.eq(ChunkDO::getEnabled, enabled);
        // total：满足当前查询条件的记录总数。
        long total = chunks.selectCount(filter);
        // rows：数据库结果或当前表格数据行集合。
        var rows =
                chunks.selectList(
                        filter.orderByAsc(ChunkDO::getChunkIndex, ChunkDO::getId)
                                .last(
                                        "LIMIT "
                                                + size
                                                + " OFFSET "
                                                + (((long) current - 1) * size)));
        return new PageResponse<>(current, size, total, rows.stream().map(this::view).toList());
    }

    /**
     * 在事务外计算新向量，再在锁内复核版本并保存人工切片变更。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param id 当前业务对象的唯一标识。
     * @param chunkId 切片唯一标识。
     * @param request 当前入口接收的请求参数。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public ChunkChangedBO save(
            KnowledgeBasePrincipal actor, UUID id, UUID chunkId, ChunkContentBO request) {
        // snapshot：本次模型调用或状态变更所依据的不可变业务快照。
        var snapshot = snapshot(actor, id, request.version(), true, true);
        if (snapshot.getActiveGeneration() == null)
            throw new KnowledgeBaseException(
                    409,
                    "KB_DOCUMENT_NOT_INDEXED",
                    "Publish the first ingestion before maintaining chunks");
        // previous：本次操作涉及的当前代次切片实体。
        ChunkDO previous = chunkId == null ? null : requireChunk(actor, snapshot, chunkId);
        // content：用于展示或维护的正文内容。
        String content = content(request.content());
        // base：当前操作涉及的知识库及其固定模型绑定。
        var base = bases.requireOwned(actor, snapshot.getKbId());
        // result：当前步骤产生的处理结果。
        var result = embed(base, List.of(content));
        return tx.execute(
                status -> {
                    // status：本次短事务的执行状态，由事务模板传入。
                    // doc：当前操作涉及的文档。
                    var doc = locked(actor, snapshot, request.version(), true, true);
                    // before：维护操作执行前的状态摘要。
                    var before = summary(doc);
                    // chunk：本次操作涉及的当前代次切片实体。
                    ChunkDO chunk =
                            previous == null ? new ChunkDO() : requireChunk(actor, doc, chunkId);
                    // now：当前业务计算使用的时间基准。
                    var now = OffsetDateTime.now(ZoneOffset.UTC);
                    if (previous == null) {
                        if (doc.getChunkCount() >= ingestion.maxChunks())
                            throw invalid("Document chunk limit reached");
                        chunk.setId(UUID.randomUUID());
                        chunk.setDocumentId(id);
                        chunk.setKbId(doc.getKbId());
                        chunk.setGeneration(doc.getActiveGeneration());
                        chunk.setTenantId(actor.tenantId());
                        chunk.setOwnerId(actor.userId());
                        chunk.setEnabled(true);
                        // index：当前元素位置或关键词索引名称。
                        int index =
                                jdbc.queryForObject(
                                        "UPDATE kb_generation SET next_chunk_index=next_chunk_index+1 WHERE document_id=? AND generation=? RETURNING next_chunk_index-1",
                                        Integer.class,
                                        id,
                                        doc.getActiveGeneration());
                        chunk.setChunkIndex(index);
                        chunk.setCreatedBy(actor.userId());
                        chunk.setCreatedAt(now);
                    }
                    // old：需要替换的旧记录或旧业务状态。
                    var old = previous == null ? Map.of() : chunkSummary(chunk);
                    chunk.setContent(content);
                    chunk.setEmbeddingText(content);
                    chunk.setContentHash(EmbeddingCatalog.sha256(content));
                    chunk.setCharCount(content.codePointCount(0, content.length()));
                    chunk.setTokenCount(estimatedTokens(content));
                    chunk.setTokenCountEstimated(true);
                    chunk.setEdited(true);
                    chunk.setMetadataJson(encode(manualMetadata()));
                    chunk.setEmbeddingModelId(base.getEmbeddingModelId());
                    chunk.setEmbeddingConfigRevision(base.getEmbeddingConfigRevision());
                    chunk.setAiConfigRevision(result.aiConfigRevision());
                    chunk.setUpdatedBy(actor.userId());
                    chunk.setUpdatedAt(now);
                    if (previous == null) chunks.insert(chunk);
                    else {
                        vectors.deleteByChunk(scope(actor, base), chunk.getId());
                        chunks.updateById(chunk);
                    }
                    vectors.write(
                            scope(actor, base),
                            List.of(record(chunk, result.vectors().get(0), manualMetadata())));
                    touch(actor, doc, true);
                    audit(
                            actor,
                            doc,
                            previous == null ? "CHUNK_CREATE" : "CHUNK_EDIT",
                            List.of(chunk.getId()),
                            Map.of("document", before, "chunk", old),
                            Map.of("document", summary(doc), "chunk", chunkSummary(chunk)));
                    return changed(doc, List.of(chunk));
                });
    }

    /**
     * 校验预期版本后删除指定切片及其向量，记录人工维护状态。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param id 当前业务对象的唯一标识。
     * @param chunkId 切片唯一标识。
     * @param version 当前记录版本或本次请求固定的版本标记。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public ChunkChangedBO delete(
            KnowledgeBasePrincipal actor, UUID id, UUID chunkId, long version) {
        return tx.execute(
                status -> {
                    // status：本次短事务的执行状态，由事务模板传入。
                    // doc：当前操作涉及的文档。
                    var doc = locked(actor, null, id, version, false, true);
                    // before：维护操作执行前的状态摘要。
                    var before = summary(doc);
                    // chunk：当前处理的切片。
                    var chunk = requireChunk(actor, doc, chunkId);
                    vectors.deleteByChunk(
                            scope(actor, bases.requireOwned(actor, doc.getKbId())), chunkId);
                    chunks.deleteById(chunkId);
                    touch(actor, doc, true);
                    audit(
                            actor,
                            doc,
                            "CHUNK_DELETE",
                            List.of(chunkId),
                            Map.of("document", before, "chunk", chunkSummary(chunk)),
                            summary(doc));
                    return changed(doc, List.of());
                });
    }

    /**
     * 批量切换切片启用状态，恢复缺失向量并同步索引变更。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param id 当前业务对象的唯一标识。
     * @param chunkIds 本次维护或审计涉及的切片标识集合。
     * @param applyAll 是否对当前文档全部切片执行维护。
     * @param enabled 是否启用该资源或能力。
     * @param version 当前记录版本或本次请求固定的版本标记。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public ChunkChangedBO enabled(
            KnowledgeBasePrincipal actor,
            UUID id,
            List<UUID> chunkIds,
            boolean applyAll,
            boolean enabled,
            long version) {
        if ((applyAll && chunkIds != null)
                || (!applyAll
                        && (chunkIds == null
                                || chunkIds.isEmpty()
                                || chunkIds.size() > config.maxBatchIds()
                                || chunkIds.stream().anyMatch(Objects::isNull)
                                || new HashSet<>(chunkIds).size() != chunkIds.size())))
            throw invalid(
                    "Choose nonempty unique chunkIds within the limit, or applyAll=true with chunkIds omitted");
        // snapshot：本次模型调用或状态变更所依据的不可变业务快照。
        var snapshot = snapshot(actor, id, version, enabled, true);
        // selected：当前选定的切片、表格行或业务记录集合。
        var selected = select(actor, snapshot, chunkIds, applyAll);
        // missing：需要补建向量的切片集合。
        var missing = enabled ? missing(selected) : List.<ChunkDO>of();
        // base：当前操作涉及的知识库及其固定模型绑定。
        var base = bases.requireOwned(actor, snapshot.getKbId());
        // result：当前步骤产生的处理结果。
        var result =
                missing.isEmpty()
                        ? null
                        : embed(base, missing.stream().map(ChunkDO::getEmbeddingText).toList());
        return tx.execute(
                status -> {
                    // status：本次短事务的执行状态，由事务模板传入。
                    // doc：当前操作涉及的文档。
                    var doc = locked(actor, snapshot, version, enabled, true);
                    // before：维护操作执行前的状态摘要。
                    var before = summary(doc);
                    // rows：数据库结果或当前表格数据行集合。
                    var rows = select(actor, doc, chunkIds, applyAll);
                    // old：需要替换的旧记录或旧业务状态。
                    var old = rows.stream().map(this::chunkSummary).toList();
                    restore(actor, base, missing, result);
                    // chunk：当前处理的切片。
                    for (var chunk : rows) {
                        chunk.setEnabled(enabled);
                        chunk.setUpdatedBy(actor.userId());
                        chunk.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
                        chunks.updateById(chunk);
                    }
                    touch(actor, doc, true);
                    audit(
                            actor,
                            doc,
                            enabled ? "CHUNK_ENABLE" : "CHUNK_DISABLE",
                            rows.stream().map(ChunkDO::getId).toList(),
                            Map.of("document", before, "chunks", old),
                            Map.of(
                                    "document",
                                    summary(doc),
                                    "chunks",
                                    rows.stream().map(this::chunkSummary).toList()));
                    return changed(doc, rows);
                });
    }

    /**
     * 切换文档启用状态，同时更新可检索范围并处理活动任务。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param id 当前业务对象的唯一标识。
     * @param enabled 是否启用该资源或能力。
     * @param version 当前记录版本或本次请求固定的版本标记。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public DocumentBO documentEnabled(
            KnowledgeBasePrincipal actor, UUID id, boolean enabled, long version) {
        // snapshot：本次模型调用或状态变更所依据的不可变业务快照。
        var snapshot = snapshot(actor, id, version, false, false);
        // selected：当前选定的切片、表格行或业务记录集合。
        var selected =
                enabled
                        ? chunks.selectList(filter(actor, snapshot).eq(ChunkDO::getEnabled, true))
                        : List.<ChunkDO>of();
        // missing：需要补建向量的切片集合。
        var missing = missing(selected);
        // base：当前操作涉及的知识库及其固定模型绑定。
        var base = bases.requireOwned(actor, snapshot.getKbId());
        // result：当前步骤产生的处理结果。
        var result =
                missing.isEmpty()
                        ? null
                        : embed(base, missing.stream().map(ChunkDO::getEmbeddingText).toList());
        return tx.execute(
                status -> {
                    // status：本次短事务的执行状态，由事务模板传入。
                    // doc：当前操作涉及的文档。
                    var doc = locked(actor, snapshot, version, false, false);
                    // before：维护操作执行前的状态摘要。
                    var before = summary(doc);
                    cancelActive(actor, id);
                    restore(actor, base, missing, result);
                    if (!enabled) refresh.stopDocument(id);
                    if ("PROCESSING".equals(doc.getProcessStatus()))
                        doc.setProcessStatus("PENDING");
                    doc.setEnabled(enabled);
                    touch(actor, doc, false);
                    audit(
                            actor,
                            doc,
                            enabled ? "DOCUMENT_ENABLE" : "DOCUMENT_DISABLE",
                            List.of(),
                            before,
                            summary(doc));
                    return documents.view(doc);
                });
    }

    /**
     * 取消文档仍处于待执行或运行状态的入库任务。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param id 当前业务对象的唯一标识。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelActive(KnowledgeBasePrincipal actor, UUID id) {
        // job：当前入库任务记录或任务标识。
        for (UUID job :
                jdbc.queryForList(
                        "SELECT id FROM kb_ingestion_job WHERE document_id=? AND status IN ('PENDING','RUNNING') ORDER BY id",
                        UUID.class,
                        id)) jobs.cancel(actor, job);
    }

    /**
     * 持久化本次维护的操作人、目标切片及变更前后摘要。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param doc 本次操作涉及的文档实体。
     * @param action 本次执行的业务操作名称或受限执行函数。
     * @param ids 本次操作涉及的业务对象标识集合。
     * @param before 维护操作执行前的状态摘要。
     * @param after 维护操作执行后的状态摘要或下一次触发的时间基准。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void audit(
            KnowledgeBasePrincipal actor,
            DocumentDO doc,
            String action,
            List<UUID> ids,
            Object before,
            Object after) {
        jdbc.update(
                """
            INSERT INTO kb_content_audit(id,document_id,kb_id,tenant_id,owner_id,actor_id,action,chunk_ids,before_summary,after_summary)
            VALUES (?,?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb)
            """,
                UUID.randomUUID(),
                doc.getId(),
                doc.getKbId(),
                actor.tenantId(),
                actor.userId(),
                actor.userId(),
                action,
                encode(ids),
                encode(before),
                encode(after));
    }

    /**
     * 生成文档启用、代次和人工维护状态的审计摘要。
     *
     * @param doc 本次操作涉及的文档实体。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public Map<String, Object> summary(DocumentDO doc) {
        return Map.of(
                "version",
                doc.getVersion(),
                "manualRevision",
                doc.getManualRevision(),
                "chunkCount",
                doc.getChunkCount(),
                "enabled",
                doc.getEnabled());
    }

    /**
     * 生成切片正文摘要和启用状态的审计快照。
     *
     * @param c 当前切片实体，用于向量补建或响应转换。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private Map<String, Object> chunkSummary(ChunkDO c) {
        return Map.of(
                "id",
                c.getId(),
                "hash",
                c.getContentHash(),
                "charCount",
                c.getCharCount(),
                "enabled",
                c.getEnabled(),
                "edited",
                c.getEdited());
    }

    /**
     * 读取并校验文档版本，作为事务外模型计算的输入基线。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param id 当前业务对象的唯一标识。
     * @param version 当前记录版本或本次请求固定的版本标记。
     * @param requireEnabled 本次操作是否要求文档处于启用状态。
     * @param rejectJob 是否因活动入库任务而拒绝当前维护操作。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private DocumentDO snapshot(
            KnowledgeBasePrincipal actor,
            UUID id,
            long version,
            boolean requireEnabled,
            boolean rejectJob) {
        // doc：当前操作涉及的文档。
        var doc = documents.requireOwned(actor, id);
        check(doc, version, requireEnabled, rejectJob);
        return doc;
    }

    /**
     * 按统一顺序锁定知识库和文档，复核模型调用前保存的版本基线。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param snapshot 本次操作涉及的文档实体。
     * @param version 当前记录版本或本次请求固定的版本标记。
     * @param requireEnabled 本次操作是否要求文档处于启用状态。
     * @param rejectJob 是否因活动入库任务而拒绝当前维护操作。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private DocumentDO locked(
            KnowledgeBasePrincipal actor,
            DocumentDO snapshot,
            long version,
            boolean requireEnabled,
            boolean rejectJob) {
        return locked(actor, snapshot, snapshot.getId(), version, requireEnabled, rejectJob);
    }

    /**
     * 按统一顺序锁定知识库和文档，复核模型调用前保存的版本基线。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param snapshot 本次操作涉及的文档实体。
     * @param id 当前业务对象的唯一标识。
     * @param version 当前记录版本或本次请求固定的版本标记。
     * @param requireEnabled 本次操作是否要求文档处于启用状态。
     * @param rejectJob 是否因活动入库任务而拒绝当前维护操作。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private DocumentDO locked(
            KnowledgeBasePrincipal actor,
            DocumentDO snapshot,
            UUID id,
            long version,
            boolean requireEnabled,
            boolean rejectJob) {
        // ids：本次操作涉及的业务对象标识集合。
        var ids =
                jdbc.queryForList(
                        "SELECT kb_id FROM kb_document WHERE id=? AND tenant_id=? AND owner_id=? AND deleted_at IS NULL",
                        UUID.class,
                        id,
                        actor.tenantId(),
                        actor.userId());
        if (ids.isEmpty())
            throw new KnowledgeBaseException(404, "KB_DOCUMENT_NOT_FOUND", "Document not found");
        bases.lockOwned(actor, ids.get(0));
        jdbc.queryForObject("SELECT id FROM kb_document WHERE id=? FOR UPDATE", UUID.class, id);
        // doc：当前操作涉及的文档。
        var doc = documents.lockOwned(actor, id);
        check(doc, version, requireEnabled, rejectJob);
        if (snapshot != null
                && !Objects.equals(snapshot.getActiveGeneration(), doc.getActiveGeneration()))
            throw conflict();
        return doc;
    }

    /**
     * 检查文档版本、启用状态、有效代次和活动任务是否允许本次维护。
     *
     * @param doc 本次操作涉及的文档实体。
     * @param version 当前记录版本或本次请求固定的版本标记。
     * @param requireEnabled 本次操作是否要求文档处于启用状态。
     * @param rejectJob 是否因活动入库任务而拒绝当前维护操作。
     */
    private void check(DocumentDO doc, long version, boolean requireEnabled, boolean rejectJob) {
        if (doc.getVersion() != version) throw conflict();
        if (requireEnabled && !doc.getEnabled())
            throw new KnowledgeBaseException(
                    409, "KB_DOCUMENT_DISABLED", "Enable the document first");
        if (rejectJob
                && Boolean.TRUE.equals(
                        jdbc.queryForObject(
                                "SELECT EXISTS(SELECT 1 FROM kb_refresh_run WHERE document_id=? AND status IN ('PENDING','RUNNING','WAITING_INGESTION'))",
                                Boolean.class,
                                doc.getId())))
            throw new KnowledgeBaseException(
                    409, "KB_REFRESH_ACTIVE", "Wait for refresh before maintaining chunks");
        if (rejectJob
                && Boolean.TRUE.equals(
                        jdbc.queryForObject(
                                "SELECT EXISTS(SELECT 1 FROM kb_ingestion_job WHERE document_id=? AND status IN ('PENDING','RUNNING'))",
                                Boolean.class,
                                doc.getId())))
            throw new KnowledgeBaseException(
                    409,
                    "KB_JOB_ACTIVE",
                    "Wait for ingestion or cancel it before maintaining chunks");
    }

    /**
     * 构造仅匹配当前用户、文档和有效代次的切片查询条件。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param doc 本次操作涉及的文档实体。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private LambdaQueryWrapper<ChunkDO> filter(KnowledgeBasePrincipal actor, DocumentDO doc) {
        return new LambdaQueryWrapper<ChunkDO>()
                .eq(ChunkDO::getDocumentId, doc.getId())
                .eq(ChunkDO::getKbId, doc.getKbId())
                .eq(ChunkDO::getTenantId, actor.tenantId())
                .eq(ChunkDO::getOwnerId, actor.userId())
                .eq(
                        ChunkDO::getGeneration,
                        doc.getActiveGeneration() == null ? -1 : doc.getActiveGeneration());
    }

    /**
     * 在当前文档的有效代次内查找切片，不存在时抛出业务异常。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param doc 本次操作涉及的文档实体。
     * @param id 当前业务对象的唯一标识。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private ChunkDO requireChunk(KnowledgeBasePrincipal actor, DocumentDO doc, UUID id) {
        // chunk：当前处理的切片。
        var chunk = chunks.selectOne(filter(actor, doc).eq(ChunkDO::getId, id));
        if (chunk == null)
            throw new KnowledgeBaseException(
                    404, "KB_CHUNK_NOT_FOUND", "Current document chunk not found");
        return chunk;
    }

    /**
     * 按全部切片或指定标识集合加载并校验维护目标。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param doc 本次操作涉及的文档实体。
     * @param ids 本次操作涉及的业务对象标识集合。
     * @param all 当前步骤收集的全部内容或是否选择全部切片。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private List<ChunkDO> select(
            KnowledgeBasePrincipal actor, DocumentDO doc, List<UUID> ids, boolean all) {
        if (doc.getActiveGeneration() == null)
            throw new KnowledgeBaseException(
                    409,
                    "KB_DOCUMENT_NOT_INDEXED",
                    "Publish the first ingestion before maintaining chunks");
        // filter：包含租户、所有者及当前业务筛选条件的查询约束。
        var filter = filter(actor, doc);
        if (!all) filter.in(ChunkDO::getId, ids);
        // rows：数据库结果或当前表格数据行集合。
        var rows = chunks.selectList(filter.orderByAsc(ChunkDO::getChunkIndex, ChunkDO::getId));
        if (!all && rows.size() != ids.size())
            throw new KnowledgeBaseException(
                    404,
                    "KB_CHUNK_NOT_FOUND",
                    "Every chunk must belong to the current document generation");
        return rows;
    }

    /**
     * 查找尚未具有向量记录的切片，供重新启用时补建。
     *
     * @param rows 数据库结果或当前表格数据行集合。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private List<ChunkDO> missing(List<ChunkDO> rows) {
        if (rows.isEmpty()) return List.of();
        // params：本次查询使用的命名参数集合。
        // 查询范围受 maxChunks 限制；一次绑定集合查询取代逐条查询，SQL 不接受客户端拼接内容。
        var params =
                new org.springframework.jdbc.core.namedparam.MapSqlParameterSource(
                        "ids", rows.stream().map(ChunkDO::getId).toList());
        // found：已经找到且满足当前查询条件的结果集合。
        var found =
                new HashSet<>(
                        new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(
                                        jdbc)
                                .queryForList(
                                        "SELECT chunk_id FROM kb_vector WHERE chunk_id IN (:ids)",
                                        params,
                                        UUID.class));
        // c：当前切片实体，用于向量补建或响应转换。
        return rows.stream().filter(c -> !found.contains(c.getId())).toList();
    }

    /**
     * 为缺少向量的切片写入与知识库绑定一致的新向量。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param base 本次操作涉及的知识库实体。
     * @param missing 需要补建向量的切片集合。
     * @param result 顺序对应输入的整组向量及实际 AI 配置修订。
     */
    private void restore(
            KnowledgeBasePrincipal actor,
            KnowledgeBaseDO base,
            List<ChunkDO> missing,
            EmbeddingResultBO result) {
        if (missing.isEmpty()) return;
        // records：当前存储记录集合或证据重载组件。
        var records = new ArrayList<VectorRecordDTO>();
        // i：当前遍历位置，按原始输入顺序递增。
        for (int i = 0; i < missing.size(); i++)
            records.add(record(missing.get(i), result.vectors().get(i), metadata(missing.get(i))));
        vectors.write(scope(actor, base), records);
    }

    /**
     * 按知识库固定绑定计算切片向量，并复核实际 AI 配置身份。
     *
     * @param base 本次操作涉及的知识库实体。
     * @param texts 本次需要计算嵌入的有序文本集合。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private EmbeddingResultBO embed(KnowledgeBaseDO base, List<String> texts) {
        if (base.getEmbeddingAiConfigRevision() == null)
            throw new KnowledgeBaseException(
                    409,
                    "KB_DOCUMENT_NOT_INDEXED",
                    "Publish the first ingestion before maintaining chunks");
        try {
            // model：当前固定的模型配置或 AI 返回的模型身份节点。
            var model = json.readTree(base.getEmbeddingConfigJson());
            // binding：本次业务固定使用的嵌入模型身份与配置快照。
            var binding =
                    new EmbeddingBindingBO(
                            base.getEmbeddingModelId(),
                            base.getEmbeddingProvider(),
                            model.path("modelName").asText(),
                            base.getEmbeddingDimension(),
                            model.path("embeddingSpace").asText(),
                            model.path("configVersion").asText(),
                            base.getEmbeddingConfigRevision(),
                            base.getEmbeddingAiConfigRevision());
            // result：当前步骤产生的处理结果。
            var result =
                    embeddings.embed(
                            binding,
                            texts,
                            "maintenance-" + UUID.randomUUID(),
                            Instant.now().plus(config.modelDeadline()));
            if (result == null
                    || result.vectors() == null
                    || result.vectors().size() != texts.size()
                    || !binding.expectedAiRevision().equals(result.aiConfigRevision()))
                throw new KnowledgeBaseException(
                        502,
                        "KB_MODEL_INVALID_RESPONSE",
                        "Maintenance embeddings do not match the current index");
            return result;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // e：本次捕获或匹配的异常，用于保留业务分类及失败原因。
            throw new IllegalStateException("Invalid persisted model binding", e);
        }
    }

    /**
     * 更新文档维护版本、人工修改标志及相关索引版本。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param doc 本次操作涉及的文档实体。
     * @param manual 本次操作是否产生人工维护标记。
     */
    private void touch(KnowledgeBasePrincipal actor, DocumentDO doc, boolean manual) {
        // count：当前累计数量或本次流读取的有效字节数。
        long count = chunks.selectCount(filter(actor, doc));
        doc.setChunkCount(count);
        if (doc.getActiveGeneration() != null)
            jdbc.update(
                    "UPDATE kb_generation SET chunk_count=? WHERE document_id=? AND generation=?",
                    count,
                    doc.getId(),
                    doc.getActiveGeneration());
        if (manual) {
            doc.setHasManualChanges(true);
            doc.setManualRevision(doc.getManualRevision() + 1);
        }
        doc.setVersion(doc.getVersion() + 1);
        doc.setUpdatedBy(actor.userId());
        doc.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        documentMapper.updateById(doc);
    }

    /**
     * 组合维护后的最新文档信息和受影响切片响应。
     *
     * @param d 本次操作涉及的文档实体。
     * @param rows 数据库结果或当前表格数据行集合。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private ChunkChangedBO changed(DocumentDO d, List<ChunkDO> rows) {
        return new ChunkChangedBO(
                d.getVersion(),
                d.getManualRevision(),
                d.getChunkCount(),
                rows.stream().map(this::view).toList());
    }

    /**
     * 将持久化切片转换为可供维护界面展示的响应。
     *
     * @param c 当前切片实体，用于向量补建或响应转换。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private ChunkViewBO view(ChunkDO c) {
        return new ChunkViewBO(
                c.getId(),
                c.getDocumentId(),
                c.getKbId(),
                c.getGeneration(),
                c.getChunkIndex(),
                c.getContent(),
                c.getEmbeddingText(),
                c.getContentHash(),
                c.getCharCount(),
                c.getTokenCount(),
                c.getTokenCountEstimated(),
                c.getEnabled(),
                c.getEdited(),
                metadata(c),
                c.getCreatedBy(),
                c.getUpdatedBy(),
                c.getCreatedAt(),
                c.getUpdatedAt());
    }

    /**
     * 读取切片来源元数据，保留无法解析时的明确失败。
     *
     * @param c 当前切片实体，用于向量补建或响应转换。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private Map<String, Object> metadata(ChunkDO c) {
        try {
            return json.readValue(c.getMetadataJson(), new TypeReference<Map<String, Object>>() {});
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // e：本次捕获或匹配的异常，用于保留业务分类及失败原因。
            throw new IllegalStateException(e);
        }
    }

    /**
     * 构造人工新增切片的来源标记。
     *
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private Map<String, Object> manualMetadata() {
        return Map.of(
                "blockType",
                "MANUAL",
                "manual",
                true,
                "originalFileContainsContent",
                false,
                "chapterPath",
                List.of(),
                "sourceBlocks",
                List.of());
    }

    /**
     * 将持久化切片及向量组合为向量存储写入记录。
     *
     * @param c 当前切片实体，用于向量补建或响应转换。
     * @param vector 当前查询或切片对应的浮点向量。
     * @param metadata 上传业务元数据或切片来源定位字段。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private VectorRecordDTO record(ChunkDO c, float[] vector, Map<String, Object> metadata) {
        return new VectorRecordDTO(
                c.getId(),
                c.getDocumentId(),
                c.getGeneration(),
                vector,
                c.getAiConfigRevision(),
                metadata);
    }

    /**
     * 根据调用身份和知识库绑定构造向量访问范围。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param b 本次操作涉及的知识库实体。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private VectorScopeDTO scope(KnowledgeBasePrincipal actor, KnowledgeBaseDO b) {
        return new VectorScopeDTO(
                actor,
                b.getId(),
                b.getVectorNamespace(),
                b.getEmbeddingModelId(),
                b.getEmbeddingConfigRevision());
    }

    /**
     * 校验并规范化人工切片正文，同时限制正文与嵌入输入长度。
     *
     * @param raw 尚未校验或规范化的原始输入。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private String content(String raw) {
        if (raw == null) throw invalid("Content is required");
        // value：当前待校验、转换或写入的值。
        String value = raw.replace("\r\n", "\n").replace('\r', '\n');
        // c：当前处理的 Unicode 码点或 SAX 字符片段。
        if (value.isBlank()
                || value.codePointCount(0, value.length())
                        > Math.min(ingestion.hardChunkChars(), ingestion.maxEmbeddingChars())
                || value.codePoints().anyMatch(c -> c == 0 || (c >= 0xd800 && c <= 0xdfff)))
            throw invalid("Content is blank, invalid, or exceeds the configured chunk budget");
        return value;
    }

    /**
     * 按文本内容估算 Token 数，结果仅用于展示。
     *
     * @param value 当前待校验、转换或写入的值。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private int estimatedTokens(String value) {
        // c：当前处理的 Unicode 码点或 SAX 字符片段。
        // ascii：当前文本中的 ASCII 字符数量。
        long ascii = value.codePoints().filter(c -> c < 128).count();
        return Math.max(
                1, (int) ((ascii + 3) / 4 + value.codePointCount(0, value.length()) - ascii));
    }

    /**
     * 将业务对象序列化为 JSON，序列化失败时保留明确异常。
     *
     * @param value 当前待校验、转换或写入的值。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // e：本次捕获或匹配的异常，用于保留业务分类及失败原因。
            throw new IllegalStateException(e);
        }
    }

    /**
     * 创建输入或协议校验失败的业务异常。
     *
     * @param m 本次输入校验失败的业务说明。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private static KnowledgeBaseException invalid(String m) {
        return new KnowledgeBaseException(400, "KB_INVALID_INPUT", m);
    }

    /**
     * 创建并发版本或业务状态冲突异常。
     *
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private static KnowledgeBaseException conflict() {
        return new KnowledgeBaseException(
                409,
                "KB_VERSION_CONFLICT",
                "Reload the document version and current generation before retrying");
    }
}
