package com.lzj.rag.backend.search.repository;

import com.lzj.rag.backend.knowledgebase.exception.KnowledgeBaseException;
import com.lzj.rag.backend.search.config.KeywordProperties;
import com.lzj.rag.backend.search.service.KnowledgeSearchService;
import com.lzj.rag.backend.search.service.bo.EvidenceBO;
import com.lzj.rag.backend.search.service.bo.SearchBO;
import com.lzj.rag.backend.security.model.KnowledgeBasePrincipal;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.*;

/** 从业务数据库重新加载当前有效证据，校验所有权、代次及关键词同步版本。 */
@Repository
public class EvidenceRepository {

    /** 执行包含授权范围条件的命名参数查询。 */
    private final NamedParameterJdbcTemplate jdbc;

    /** 校验检索范围并执行向量召回的业务服务。 */
    private final KnowledgeSearchService sources;

    /** 关键词检索开关、索引身份、分析器及同步工作线程配置。 */
    private final KeywordProperties config;

    /**
     * 初始化当前组件，保存业务依赖及本次使用的配置。
     *
     * @param jdbc 执行包含授权范围条件的命名参数查询。
     * @param sources 校验检索范围并执行向量召回的业务服务。
     * @param config 关键词检索开关、索引身份、分析器及同步工作线程配置。
     */
    public EvidenceRepository(
            NamedParameterJdbcTemplate jdbc,
            KnowledgeSearchService sources,
            KeywordProperties config) {
        this.jdbc = jdbc;
        this.sources = sources;
        this.config = config;
    }

    /**
     * 读取请求范围内已发布且可见的关键词版本键。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param request 当前入口接收的请求参数。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public List<String> versionKeys(KnowledgeBasePrincipal actor, SearchBO request) {
        // p：包含当前身份及业务范围约束的命名 SQL 参数。
        var p = params(actor, request);
        p.put("target", config.target());
        p.put("limit", config.maxScopeDocuments() + 1);
        // keys：本次查询或索引操作使用的键集合。
        var keys =
                jdbc.queryForList(
                        """
            SELECT s.document_id::text||':'||s.synced_revision::text FROM kb_keyword_sync s
            JOIN kb_document d ON d.id=s.document_id JOIN kb_knowledge_base b ON b.id=d.kb_id
            WHERE s.tenant_id=:tenant AND s.owner_id=:owner AND d.tenant_id=:tenant AND d.owner_id=:owner
            AND b.tenant_id=:tenant AND b.owner_id=:owner AND b.id IN (:kbs) AND (:allDocs OR d.id IN (:docs))
            AND d.enabled AND d.deleted_at IS NULL AND b.deleted_at IS NULL AND d.active_generation IS NOT NULL
            AND s.revision=s.synced_revision AND s.indexed_target=:target ORDER BY d.id LIMIT :limit
            """,
                        p,
                        String.class);
        if (keys.size() > config.maxScopeDocuments())
            throw new KnowledgeBaseException(
                    400,
                    "KB_KEYWORD_SCOPE_LIMIT",
                    "Narrow keyword document scope to the configured document quota");
        return keys;
    }

    /**
     * 按切片集合从数据库重载仍有权限、已启用且属于有效代次的证据。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param request 当前入口接收的请求参数。
     * @param ids 本次操作涉及的业务对象标识集合。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public Map<UUID, EvidenceBO> hydrate(
            KnowledgeBasePrincipal actor, SearchBO request, Collection<UUID> ids) {
        if (ids.isEmpty()) return Map.of();
        // p：包含当前身份及业务范围约束的命名 SQL 参数。
        var p = params(actor, request);
        p.put("ids", ids);
        // result：当前步骤产生的处理结果。
        var result = new LinkedHashMap<UUID, EvidenceBO>();
        jdbc.query(
                """
            SELECT c.*,d.name AS document_name FROM kb_chunk c JOIN kb_document d ON d.id=c.document_id
            JOIN kb_knowledge_base b ON b.id=d.kb_id JOIN kb_generation g ON g.document_id=d.id AND g.generation=c.generation
            WHERE c.id IN (:ids) AND b.id IN (:kbs) AND (:allDocs OR d.id IN (:docs))
            AND c.tenant_id=:tenant AND c.owner_id=:owner AND d.tenant_id=:tenant AND d.owner_id=:owner
            AND b.tenant_id=:tenant AND b.owner_id=:owner AND c.kb_id=d.kb_id AND c.generation=d.active_generation
            AND c.enabled AND d.enabled AND d.deleted_at IS NULL AND b.deleted_at IS NULL
            AND c.embedding_config_revision=b.embedding_config_revision AND c.ai_config_revision=b.embedding_ai_config_revision
            AND g.embedding_config_revision=b.embedding_config_revision AND g.ai_config_revision=b.embedding_ai_config_revision
            """,
                p,
                (rs, n) -> {
                    // rs：数据库查询的当前结果行。
                    // n：当前结果行的顺序编号，由 JDBC 行映射器传入。
                    // id：当前业务对象的唯一标识。
                    UUID id = rs.getObject("id", UUID.class);
                    // doc：本次操作涉及的文档唯一标识。
                    UUID doc = rs.getObject("document_id", UUID.class);
                    result.put(
                            id,
                            new EvidenceBO(
                                    id,
                                    rs.getObject("kb_id", UUID.class),
                                    doc,
                                    rs.getString("document_name"),
                                    rs.getInt("chunk_index"),
                                    rs.getLong("generation"),
                                    rs.getString("content"),
                                    0,
                                    "UNSCORED",
                                    sources.source(rs.getString("metadata_json")),
                                    "/api/documents/" + doc + "/file"));
                    return id;
                });
        return result;
    }

    /**
     * 读取当前可见切片对应的关键词同步修订。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param request 当前入口接收的请求参数。
     * @param ids 本次操作涉及的业务对象标识集合。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public Map<UUID, Long> validKeywordRevisions(
            KnowledgeBasePrincipal actor, SearchBO request, Collection<UUID> ids) {
        if (ids.isEmpty()) return Map.of();
        // p：包含当前身份及业务范围约束的命名 SQL 参数。
        var p = params(actor, request);
        p.put("ids", ids);
        p.put("target", config.target());
        // result：当前步骤产生的处理结果。
        var result = new HashMap<UUID, Long>();
        jdbc.query(
                "SELECT c.id,s.synced_revision FROM kb_chunk c JOIN kb_keyword_sync s ON s.document_id=c.document_id WHERE c.id IN (:ids) AND s.tenant_id=:tenant AND s.owner_id=:owner AND s.revision=s.synced_revision AND s.indexed_target=:target",
                p,
                (rs, n) -> {
                    // rs：数据库查询的当前结果行。
                    // n：当前结果行的顺序编号，由 JDBC 行映射器传入。
                    result.put(rs.getObject(1, UUID.class), rs.getLong(2));
                    return 0;
                });
        return result;
    }

    /**
     * 构造限定当前身份、知识库及可选文档集合的命名 SQL 参数。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param request 当前入口接收的请求参数。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private Map<String, Object> params(KnowledgeBasePrincipal actor, SearchBO request) {
        // docs：当前范围内的文档记录集合。
        var docs = request.documentIds() == null ? List.<UUID>of() : request.documentIds();
        return new HashMap<>(
                Map.of(
                        "tenant",
                        actor.tenantId(),
                        "owner",
                        actor.userId(),
                        "kbs",
                        request.kbIds(),
                        "docs",
                        docs.isEmpty() ? List.of(new UUID(0, 0)) : docs,
                        "allDocs",
                        docs.isEmpty()));
    }
}
