package com.lzj.rag.backend.search.repository.vector;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lzj.rag.backend.ingestion.config.IngestionProperties;
import com.lzj.rag.backend.knowledgebase.dao.entity.KnowledgeBaseDO;
import com.lzj.rag.backend.knowledgebase.exception.KnowledgeBaseException;
import com.lzj.rag.backend.knowledgebase.service.KnowledgeBaseService;
import com.lzj.rag.backend.search.repository.vector.dto.VectorHitDTO;
import com.lzj.rag.backend.search.repository.vector.dto.VectorRecordDTO;
import com.lzj.rag.backend.search.repository.vector.dto.VectorScopeDTO;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

import java.util.*;

/** 以 PostgreSQL 和 pgvector 存取向量，先按权限及当前代次筛选再计算相似度。 */
@Component
public class PgvectorVectorStore implements VectorStore {

    /** 执行数据库查询及受条件保护的业务状态更新。 */
    private final JdbcTemplate jdbc;

    /** 负责知识库归属、版本与固定模型绑定的业务服务。 */
    private final KnowledgeBaseService bases;

    /** 用于业务请求、持久化快照与元数据转换的 JSON 映射器。 */
    private final ObjectMapper json;

    /** 入库流水线的时间预算、文本长度、切片数量和向量写入限制。 */
    private final IngestionProperties config;

    /**
     * 初始化当前组件，保存业务依赖及本次使用的配置。
     *
     * @param jdbc 执行数据库查询及受条件保护的业务状态更新。
     * @param bases 负责知识库归属、版本与固定模型绑定的业务服务。
     * @param json 用于业务请求、持久化快照与元数据转换的 JSON 映射器。
     * @param config 入库流水线的时间预算、文本长度、切片数量和向量写入限制。
     */
    public PgvectorVectorStore(
            JdbcTemplate jdbc,
            KnowledgeBaseService bases,
            ObjectMapper json,
            IngestionProperties config) {
        this.jdbc = jdbc;
        this.bases = bases;
        this.json = json;
        this.config = config;
    }

    /**
     * 在固定权限和命名空间内分批写入切片向量及其元数据。
     *
     * @param scope 限制所有权、知识库和向量命名空间的访问范围。
     * @param records 当前存储记录集合或证据重载组件。
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void write(VectorScopeDTO scope, List<VectorRecordDTO> records) {
        authorize(scope);
        jdbc.batchUpdate(
                """
            INSERT INTO kb_vector(chunk_id,kb_id,document_id,generation,tenant_id,owner_id,vector_namespace,embedding_model_id,
            embedding_config_revision,ai_config_revision,embedding,metadata_json) VALUES (?,?,?,?,?,?,?,?,?,?,?::public.vector,?::jsonb)
            """,
                records,
                config.writeBatchSize(),
                (ps, row) -> {
                    // ps：当前批量写入记录使用的 JDBC 预编译语句。
                    // row：当前数据库记录或表格数据行。
                    ps.setObject(1, row.chunkId());
                    ps.setObject(2, scope.kbId());
                    ps.setObject(3, row.documentId());
                    ps.setLong(4, row.generation());
                    ps.setString(5, scope.actor().tenantId());
                    ps.setString(6, scope.actor().userId());
                    ps.setString(7, scope.namespace());
                    ps.setString(8, scope.modelId());
                    ps.setString(9, scope.configRevision());
                    ps.setString(10, row.aiConfigRevision());
                    ps.setString(11, literal(row.embedding()));
                    ps.setString(12, encode(row.metadata()));
                });
    }

    /**
     * 在授权范围和有效代次内按余弦距离召回有限数量的切片。
     *
     * @param scope 限制所有权、知识库和向量命名空间的访问范围。
     * @param vector 当前查询或切片对应的浮点向量。
     * @param limit 当前查询数量、下载字节数或解析字符预算的上限。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @Override
    public List<VectorHitDTO> query(VectorScopeDTO scope, float[] vector, int limit) {
        // base：当前操作涉及的知识库及其固定模型绑定。
        var base = authorize(scope);
        literal(vector);
        checkLimit(limit);
        if (base.getEmbeddingAiConfigRevision() == null) return List.of();
        return query(
                List.of(scope),
                vector,
                base.getEmbeddingAiConfigRevision(),
                List.of(),
                limit,
                null);
    }

    /**
     * 在授权范围和有效代次内按余弦距离召回有限数量的切片。
     *
     * @param scopes 本次向量检索允许访问的范围集合。
     * @param vector 当前查询或切片对应的浮点向量。
     * @param aiConfigRevision 本次实际使用或任务固定的 AI 配置修订摘要。
     * @param documentIds 限定检索范围的文档标识集合。
     * @param limit 当前查询数量、下载字节数或解析字符预算的上限。
     * @param minScore 仅适用于向量通道的最低相似度阈值。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @Override
    public List<VectorHitDTO> query(
            List<VectorScopeDTO> scopes,
            float[] vector,
            String aiConfigRevision,
            List<UUID> documentIds,
            int limit,
            Double minScore) {
        checkLimit(limit);
        if (scopes == null
                || scopes.isEmpty()
                || scopes.size() > 64
                || documentIds == null
                || documentIds.size() > 500
                || aiConfigRevision == null
                || !aiConfigRevision.matches("[0-9a-f]{64}")
                || (minScore != null
                        && (!Double.isFinite(minScore) || minScore < -1 || minScore > 1)))
            throw new KnowledgeBaseException(
                    400, "KB_INVALID_INPUT", "Invalid vector query scope or score threshold");
        // first：当前处理范围的起始位置或首个有效内容。
        var first = scopes.get(0);
        // ids：本次操作涉及的业务对象标识集合。
        var ids = new LinkedHashSet<UUID>();
        // scope：限制所有权、知识库和向量命名空间的访问范围。
        for (var scope : scopes) {
            if (scope == null
                    || !first.actor().tenantId().equals(scope.actor().tenantId())
                    || !first.actor().userId().equals(scope.actor().userId())
                    || !first.modelId().equals(scope.modelId())
                    || !first.configRevision().equals(scope.configRevision())
                    || !ids.add(scope.kbId()))
                throw new KnowledgeBaseException(
                        409,
                        "KB_SEARCH_MODEL_MISMATCH",
                        "Vector query must use one authorized embedding space");
            // base：当前操作涉及的知识库及其固定模型绑定。
            var base = authorize(scope);
            if (base.getEmbeddingAiConfigRevision() != null
                    && !aiConfigRevision.equals(base.getEmbeddingAiConfigRevision()))
                throw new KnowledgeBaseException(
                        409,
                        "KB_MODEL_CONFIG_CHANGED",
                        "Query revision does not match the current index");
        }
        // parameters：本次模型或数据库调用的参数集合。
        var parameters =
                new MapSqlParameterSource()
                        .addValue("tenant", first.actor().tenantId())
                        .addValue("owner", first.actor().userId())
                        .addValue("kbIds", ids)
                        .addValue("modelId", first.modelId())
                        .addValue("localRevision", first.configRevision())
                        .addValue("aiRevision", aiConfigRevision)
                        .addValue("allDocuments", documentIds.isEmpty())
                        .addValue(
                                "documentIds",
                                documentIds.isEmpty() ? List.of(new UUID(0, 0)) : documentIds)
                        .addValue("vector", literal(vector))
                        .addValue("limit", limit)
                        .addValue("minScore", minScore, java.sql.Types.DOUBLE);
        // rs：数据库查询的当前结果行。
        // n：当前结果行的顺序编号，由 JDBC 行映射器传入。
        // 精确向量查询先过滤归属、启用状态和有效代次，再排序及截取，避免事后过滤导致召回缺失。
        return new NamedParameterJdbcTemplate(jdbc)
                .query(
                        """
            WITH candidates AS MATERIALIZED (
                SELECT v.embedding,c.id,c.kb_id,c.document_id,d.name AS document_name,c.generation,c.chunk_index,c.content,c.embedding_text,c.metadata_json
                FROM kb_vector v JOIN kb_chunk c ON c.id=v.chunk_id
                JOIN kb_document d ON d.id=c.document_id JOIN kb_knowledge_base b ON b.id=d.kb_id
                JOIN kb_generation g ON g.document_id=c.document_id AND g.generation=c.generation
                WHERE v.tenant_id=:tenant AND v.owner_id=:owner AND v.kb_id IN (:kbIds)
                AND b.tenant_id=v.tenant_id AND b.owner_id=v.owner_id AND b.deleted_at IS NULL
                AND d.deleted_at IS NULL AND d.enabled AND c.enabled AND c.generation=d.active_generation
                AND d.tenant_id=v.tenant_id AND d.owner_id=v.owner_id AND c.tenant_id=v.tenant_id AND c.owner_id=v.owner_id
                AND d.kb_id=v.kb_id AND c.kb_id=v.kb_id AND v.document_id=d.id AND v.generation=c.generation
                AND v.vector_namespace=b.vector_namespace AND v.embedding_model_id=b.embedding_model_id
                AND v.ai_config_revision=b.embedding_ai_config_revision
                AND v.ai_config_revision=:aiRevision
                AND v.embedding_config_revision=b.embedding_config_revision AND c.embedding_config_revision=v.embedding_config_revision
                AND c.embedding_model_id=v.embedding_model_id AND g.ai_config_revision=v.ai_config_revision AND c.ai_config_revision=v.ai_config_revision
                AND g.embedding_config_revision=v.embedding_config_revision
                AND v.embedding_model_id=:modelId AND v.embedding_config_revision=:localRevision
                AND (:allDocuments OR d.id IN (:documentIds))
            ), scored AS MATERIALIZED (
                SELECT *,embedding OPERATOR(public.<=>) CAST(:vector AS public.vector) AS distance FROM candidates
            ) SELECT * FROM scored WHERE CAST(:minScore AS double precision) IS NULL
                OR GREATEST(-1.0,LEAST(1.0,1.0-distance))>=:minScore
            ORDER BY distance,id LIMIT :limit
            """,
                        parameters,
                        (rs, n) ->
                                new VectorHitDTO(
                                        rs.getObject("id", UUID.class),
                                        rs.getObject("kb_id", UUID.class),
                                        rs.getObject("document_id", UUID.class),
                                        rs.getString("document_name"),
                                        rs.getLong("generation"),
                                        rs.getInt("chunk_index"),
                                        rs.getString("content"),
                                        rs.getString("embedding_text"),
                                        rs.getString("metadata_json"),
                                        rs.getDouble("distance")));
    }

    /**
     * 删除指定文档及代次范围内的向量数据。
     *
     * @param scope 限制所有权、知识库和向量命名空间的访问范围。
     * @param documentId 文档唯一标识。
     * @param generation 切片或任务所属的数据代次。
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void deleteByDocument(VectorScopeDTO scope, UUID documentId, long generation) {
        authorize(scope);
        jdbc.update(
                "DELETE FROM kb_vector WHERE kb_id=? AND tenant_id=? AND owner_id=? AND document_id=? AND generation=?",
                scope.kbId(),
                scope.actor().tenantId(),
                scope.actor().userId(),
                documentId,
                generation);
    }

    /**
     * 删除指定权限范围内单个切片的向量数据。
     *
     * @param scope 限制所有权、知识库和向量命名空间的访问范围。
     * @param chunkId 切片唯一标识。
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void deleteByChunk(VectorScopeDTO scope, UUID chunkId) {
        authorize(scope);
        jdbc.update(
                "DELETE FROM kb_vector WHERE kb_id=? AND tenant_id=? AND owner_id=? AND chunk_id=?",
                scope.kbId(),
                scope.actor().tenantId(),
                scope.actor().userId(),
                chunkId);
    }

    /**
     * 复核向量范围归属及其命名空间与知识库固定绑定一致。
     *
     * @param scope 限制所有权、知识库和向量命名空间的访问范围。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private KnowledgeBaseDO authorize(VectorScopeDTO scope) {
        // base：当前操作涉及的知识库及其固定模型绑定。
        var base = bases.requireOwned(scope.actor(), scope.kbId());
        if (!Objects.equals(scope.namespace(), base.getVectorNamespace())
                || !Objects.equals(scope.modelId(), base.getEmbeddingModelId())
                || !Objects.equals(scope.configRevision(), base.getEmbeddingConfigRevision()))
            throw new KnowledgeBaseException(
                    409,
                    "KB_MODEL_CONFIG_CHANGED",
                    "Vector scope does not match the bound knowledge base");
        return base;
    }

    /**
     * 检查向量召回数量处于允许范围。
     *
     * @param limit 当前查询数量、下载字节数或解析字符预算的上限。
     */
    private static void checkLimit(int limit) {
        if (limit < 1 || limit > 200)
            throw new KnowledgeBaseException(
                    400, "KB_INVALID_INPUT", "Vector result limit must be 1..200");
    }

    /**
     * 将有限浮点向量编码为 pgvector 字面量，并拒绝零向量或无效数值。
     *
     * @param vector 当前查询或切片对应的浮点向量。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public static String literal(float[] vector) {
        if (vector == null || vector.length != 1024) throw invalid();
        // nonzero：当前向量是否包含至少一个非零元素。
        boolean nonzero = false;
        // text：当前待处理或生成的文本内容。
        var text = new StringBuilder("[");
        // i：当前输入向量或向量维度的位置索引。
        for (int i = 0; i < vector.length; i++) {
            if (!Float.isFinite(vector[i])) throw invalid();
            nonzero |= vector[i] != 0;
            if (i > 0) text.append(',');
            text.append(Float.toString(vector[i]));
        }
        if (!nonzero) throw invalid();
        return text.append(']').toString();
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
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private static KnowledgeBaseException invalid() {
        return new KnowledgeBaseException(
                502,
                "KB_MODEL_INVALID_RESPONSE",
                "A finite nonzero 1024-dimensional vector is required");
    }
}
