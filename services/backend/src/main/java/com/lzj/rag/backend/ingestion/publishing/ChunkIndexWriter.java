package com.lzj.rag.backend.ingestion.publishing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lzj.rag.backend.chunk.service.bo.ChunkBO;
import com.lzj.rag.backend.ingestion.config.IngestionProperties;
import com.lzj.rag.backend.ingestion.service.IngestionJobService;
import com.lzj.rag.backend.ingestion.service.bo.IngestionContextBO;
import com.lzj.rag.backend.knowledgebase.exception.KnowledgeBaseException;
import com.lzj.rag.backend.knowledgebase.service.KnowledgeBaseService;
import com.lzj.rag.backend.model.service.bo.EmbeddingResultBO;
import com.lzj.rag.backend.refresh.state.RefreshState;
import com.lzj.rag.backend.search.repository.vector.VectorStore;
import com.lzj.rag.backend.search.repository.vector.dto.VectorRecordDTO;
import com.lzj.rag.backend.search.repository.vector.dto.VectorScopeDTO;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** 在同一事务内写入切片和向量并发布新代次；加锁顺序为知识库、文档、任务。 */
@Service
public class ChunkIndexWriter {

    /** 执行数据库查询及受条件保护的业务状态更新。 */
    private final JdbcTemplate jdbc;

    /** 在固定所有权及命名空间内读写切片向量的存储组件。 */
    private final VectorStore vectors;

    /** 负责知识库归属、版本与固定模型绑定的业务服务。 */
    private final KnowledgeBaseService bases;

    /** 负责持久化入库任务及租约状态迁移的业务服务。 */
    private final IngestionJobService jobs;

    /** 用于业务请求、持久化快照与元数据转换的 JSON 映射器。 */
    private final ObjectMapper json;

    /** 入库流水线的时间预算、文本长度、切片数量和向量写入限制。 */
    private final IngestionProperties config;

    /** 负责刷新凭据、源对象切换及终态维护的组件。 */
    private final RefreshState refresh;

    /**
     * 初始化当前组件，保存业务依赖及本次使用的配置。
     *
     * @param jdbc 执行数据库查询及受条件保护的业务状态更新。
     * @param vectors 在固定所有权及命名空间内读写切片向量的存储组件。
     * @param bases 负责知识库归属、版本与固定模型绑定的业务服务。
     * @param jobs 负责持久化入库任务及租约状态迁移的业务服务。
     * @param json 用于业务请求、持久化快照与元数据转换的 JSON 映射器。
     * @param config 入库流水线的时间预算、文本长度、切片数量和向量写入限制。
     * @param refresh 负责刷新凭据、源对象切换及终态维护的组件。
     */
    public ChunkIndexWriter(
            JdbcTemplate jdbc,
            VectorStore vectors,
            KnowledgeBaseService bases,
            IngestionJobService jobs,
            ObjectMapper json,
            IngestionProperties config,
            RefreshState refresh) {
        this.jdbc = jdbc;
        this.vectors = vectors;
        this.bases = bases;
        this.jobs = jobs;
        this.json = json;
        this.config = config;
        this.refresh = refresh;
    }

    /**
     * 锁定并复核任务租约与输入快照，写入新代次后原子切换当前可检索版本。
     *
     * @param context 本次入库尝试固定的输入快照及租约凭据。
     * @param chunks 本次处理的有序切片集合。
     * @param embeddings 顺序对应输入的整组向量及实际 AI 配置修订。
     */
    @Transactional(timeout = 30)
    public void writeAndPublish(
            IngestionContextBO context, List<ChunkBO> chunks, EmbeddingResultBO embeddings) {
        // 第一阶段：按知识库、文档、任务顺序加锁，复核租约和全部输入版本。
        // base：当前操作涉及的知识库及其固定模型绑定。
        var base = bases.lockOwned(context.actor(), context.kbId());
        // document：锁定后的文档数据库快照，用于发布前复核版本和有效状态。
        var document =
                jdbc.queryForMap(
                        "SELECT * FROM kb_document WHERE id=? FOR UPDATE", context.documentId());
        // job：当前入库任务记录或任务标识。
        var job =
                jdbc.queryForMap(
                        "SELECT *,lease_until>clock_timestamp() AS valid FROM kb_ingestion_job WHERE id=? FOR UPDATE",
                        context.jobId());
        if (!"RUNNING".equals(job.get("status"))
                || !Boolean.TRUE.equals(job.get("valid"))
                || ((Number) job.get("claim_token")).longValue() != context.claimToken()
                || !context.leaseOwner().equals(job.get("lease_owner"))
                || ((Number) job.get("attempts")).intValue() != context.attempt()
                || !context.jobId().equals(document.get("latest_job_id"))
                || ((Number) document.get("version")).longValue() != context.documentVersion()
                || ((Number) document.get("config_revision")).longValue()
                        != context.configRevision()
                || document.get("deleted_at") != null
                || !Boolean.TRUE.equals(document.get("enabled"))
                || !base.getEmbeddingConfigRevision().equals(context.binding().localRevision())
                || !Objects.equals(job.get("ai_config_revision"), embeddings.aiConfigRevision()))
            throw new KnowledgeBaseException(
                    409,
                    "KB_INGESTION_INPUT_CHANGED",
                    "Ingestion input or worker lease changed; run again with the current configuration");
        // 第二阶段：校验切片和向量一一对应，并确认刷新候选与模型身份仍可发布。
        if (chunks.isEmpty()
                || chunks.size() > config.maxChunks()
                || chunks.size() != embeddings.vectors().size()
                || !embeddings.aiConfigRevision().matches("[0-9a-f]{64}"))
            throw new KnowledgeBaseException(
                    502,
                    "KB_MODEL_INVALID_RESPONSE",
                    "Chunk/vector counts or identity do not match");
        refresh.beforePublish(context);
        if (base.getEmbeddingAiConfigRevision() != null
                && !base.getEmbeddingAiConfigRevision().equals(embeddings.aiConfigRevision()))
            throw new KnowledgeBaseException(
                    409,
                    "KB_MODEL_CONFIG_CHANGED",
                    "AI revision is already pinned; migrate using a new knowledge base");
        if (base.getEmbeddingAiConfigRevision() == null)
            jdbc.update(
                    "UPDATE kb_knowledge_base SET embedding_ai_config_revision=?,updated_at=clock_timestamp() WHERE id=?",
                    embeddings.aiConfigRevision(),
                    context.kbId());
        // 第三阶段：先写入未生效的新代次、切片和向量；任一步失败由事务整体回滚。
        jdbc.update(
                """
            INSERT INTO kb_generation(document_id,generation,kb_id,tenant_id,owner_id,job_id,embedding_config_revision,ai_config_revision,chunk_count,next_chunk_index)
            VALUES (?,?,?,?,?,?,?,?,?,?)
            """,
                context.documentId(),
                context.generation(),
                context.kbId(),
                context.actor().tenantId(),
                context.actor().userId(),
                context.jobId(),
                context.binding().localRevision(),
                embeddings.aiConfigRevision(),
                chunks.size(),
                chunks.size());
        jdbc.batchUpdate(
                """
            INSERT INTO kb_chunk(id,kb_id,document_id,generation,tenant_id,owner_id,chunk_index,content,embedding_text,content_hash,char_count,
            metadata_json,embedding_model_id,embedding_config_revision,ai_config_revision,created_by,updated_by) VALUES (?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?,?,?)
            """,
                chunks,
                config.writeBatchSize(),
                (ps, chunk) -> {
                    // ps：当前批量写入记录使用的 JDBC 预编译语句。
                    // chunk：当前处理的切片。
                    ps.setObject(1, chunk.id());
                    ps.setObject(2, context.kbId());
                    ps.setObject(3, context.documentId());
                    ps.setLong(4, context.generation());
                    ps.setString(5, context.actor().tenantId());
                    ps.setString(6, context.actor().userId());
                    ps.setInt(7, chunk.chunkIndex());
                    ps.setString(8, chunk.content());
                    ps.setString(9, chunk.embeddingText());
                    ps.setString(10, chunk.contentHash());
                    ps.setInt(11, chunk.charCount());
                    ps.setString(12, encode(chunk.metadata()));
                    ps.setString(13, context.binding().modelId());
                    ps.setString(14, context.binding().localRevision());
                    ps.setString(15, embeddings.aiConfigRevision());
                    ps.setString(16, context.actor().userId());
                    ps.setString(17, context.actor().userId());
                });
        // 按原始切片顺序关联向量，不能使用供应商返回顺序之外的隐式匹配。
        // rows：按切片顺序与嵌入结果一一配对的向量写入记录。
        var rows = new ArrayList<VectorRecordDTO>();
        // i：当前遍历位置，按原始输入顺序递增。
        for (int i = 0; i < chunks.size(); i++)
            rows.add(
                    new VectorRecordDTO(
                            chunks.get(i).id(),
                            context.documentId(),
                            context.generation(),
                            embeddings.vectors().get(i),
                            embeddings.aiConfigRevision(),
                            chunks.get(i).metadata()));
        vectors.write(scope(context), rows);
        jobs.end(context, "PERSIST", chunks.size(), "chunks");
        // 第四阶段：以有效执行凭据结束任务，再切换文档当前代次和源对象。
        jobs.begin(context, "PUBLISH", chunks.size(), "chunks");
        jobs.end(context, "PUBLISH", 1, "generations");
        if (jdbc.update(
                        "UPDATE kb_ingestion_job SET status='SUCCEEDED',completed_at=clock_timestamp(),lease_owner=NULL,lease_until=NULL,attempt_deadline=NULL,next_run_at=NULL WHERE id=? AND status='RUNNING' AND claim_token=? AND lease_owner=? AND attempts=? AND lease_until>clock_timestamp() AND attempt_deadline>clock_timestamp()",
                        context.jobId(),
                        context.claimToken(),
                        context.leaseOwner(),
                        context.attempt())
                != 1)
            throw new KnowledgeBaseException(
                    409, "KB_JOB_EXPIRED", "Ingestion expired before publication");
        jdbc.update(
                "UPDATE kb_document SET active_generation=?,chunk_count=?,has_manual_changes=FALSE,process_status='READY',last_ingested_at=clock_timestamp(),updated_at=clock_timestamp() WHERE id=?",
                context.generation(),
                chunks.size(),
                context.documentId());
        refresh.published(context);
        // 第五阶段：旧代次只登记异步回收，发布事务不承担全部清理开销。
        // previous：发布前仍有效的旧代次编号，切换后登记后台回收。
        if (document.get("active_generation") instanceof Number previous
                && previous.longValue() != context.generation())
            jobs.queueCleanup(context.documentId(), previous.longValue());
    }

    /**
     * 从入库快照构造具有相同所有权和命名空间的向量写入范围。
     *
     * @param context 本次入库尝试固定的输入快照及租约凭据。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public static VectorScopeDTO scope(IngestionContextBO context) {
        return new VectorScopeDTO(
                context.actor(),
                context.kbId(),
                context.namespace(),
                context.binding().modelId(),
                context.binding().localRevision());
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
}
