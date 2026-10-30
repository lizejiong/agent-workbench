package com.lzj.rag.backend.operations.service.impl;

import com.fasterxml.jackson.databind.*;
import com.lzj.rag.backend.document.service.bo.IngestionSpecBO;
import com.lzj.rag.backend.knowledgebase.service.KnowledgeBaseService;
import com.lzj.rag.backend.operations.service.ManagementService;
import com.lzj.rag.backend.operations.service.bo.ContentAuditBO;
import com.lzj.rag.backend.security.model.KnowledgeBasePrincipal;
import com.lzj.rag.framework.convention.page.PageResponse;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.*;

import java.time.OffsetDateTime;
import java.util.*;

/** 提供管理导航及权限范围内的维护审计查询。 */
@org.springframework.stereotype.Service()
public class ManagementServiceImpl implements ManagementService {

    /** 执行数据库查询及受条件保护的业务状态更新。 */
    private final JdbcTemplate jdbc;

    /** 负责知识库归属、版本与固定模型绑定的业务服务。 */
    private final KnowledgeBaseService bases;

    /** 用于业务请求、持久化快照与元数据转换的 JSON 映射器。 */
    private final ObjectMapper json;

    /**
     * 初始化当前组件，保存业务依赖及本次使用的配置。
     *
     * @param jdbc 执行数据库查询及受条件保护的业务状态更新。
     * @param bases 负责知识库归属、版本与固定模型绑定的业务服务。
     * @param json 用于业务请求、持久化快照与元数据转换的 JSON 映射器。
     */
    public ManagementServiceImpl(JdbcTemplate jdbc, KnowledgeBaseService bases, ObjectMapper json) {
        this.jdbc = jdbc;
        this.bases = bases;
        this.json = json;
    }

    /**
     * 返回管理入口及服务能力导航。
     *
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public Map<String, Object> entry() {
        return Map.of(
                "delivery",
                "API_ONLY",
                "openapi",
                "/openapi/knowledge-base.yaml",
                "collection",
                "/openapi/knowledge-base.postman_collection.json",
                "capabilities",
                "/api/knowledge/search-capabilities",
                "knowledgeBases",
                "/api/kbs",
                "ingestionDefaults",
                IngestionSpecBO.defaults(),
                "workflow",
                List.of(
                        "Create KB using a server model profile",
                        "Upload or import URL",
                        "Submit durable job and poll job/stages",
                        "Retrieve evidence and open its authorized fileUrl",
                        "Maintain chunks using current document version",
                        "Configure explicit cron/timezone for URL documents"),
                "polling",
                Map.of(
                        "activeIntervalSeconds",
                        2,
                        "hiddenIntervalSeconds",
                        30,
                        "terminalStates",
                        List.of("SUCCEEDED", "FAILED", "CANCELLED")),
                "conflicts",
                Map.of(
                        "version",
                        "409 KB_VERSION_CONFLICT: reload version before retry",
                        "manualOverwrite",
                        "409 KB_MANUAL_OVERWRITE_REQUIRED: review changes, send confirmOverwriteEdited=true and expectedDocumentVersion",
                        "activeJob",
                        "409 KB_JOB_ACTIVE / KB_REFRESH_ACTIVE: inspect existing work"));
    }

    /**
     * 在知识库归属范围内分页读取维护审计记录。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param kbId 知识库唯一标识。
     * @param current 当前分页页码，从一开始。
     * @param size 分页容量或当前数据的字节数。
     * @param action 本次执行的业务操作名称或受限执行函数。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 10)
    public PageResponse<ContentAuditBO> audit(
            KnowledgeBasePrincipal actor, UUID kbId, int current, int size, String action) {
        bases.requireOwned(actor, kbId);
        // filter：包含租户、所有者及当前业务筛选条件的查询约束。
        String filter = " WHERE kb_id=? AND tenant_id=? AND owner_id=? AND (?='' OR action=?)";
        // total：满足当前查询条件的记录总数。
        long total =
                jdbc.queryForObject(
                        "SELECT count(*) FROM kb_content_audit" + filter,
                        Long.class,
                        kbId,
                        actor.tenantId(),
                        actor.userId(),
                        action,
                        action);
        // rs：数据库查询的当前结果行。
        // n：当前结果行的顺序编号，由 JDBC 行映射器传入。
        // records：当前存储记录集合或证据重载组件。
        var records =
                jdbc.query(
                        "SELECT * FROM kb_content_audit"
                                + filter
                                + " ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
                        (rs, n) ->
                                new ContentAuditBO(
                                        rs.getObject("id", UUID.class),
                                        rs.getObject("document_id", UUID.class),
                                        rs.getString("actor_id"),
                                        rs.getString("action"),
                                        tree(rs.getString("chunk_ids")),
                                        tree(rs.getString("before_summary")),
                                        tree(rs.getString("after_summary")),
                                        rs.getObject("created_at", OffsetDateTime.class)),
                        kbId,
                        actor.tenantId(),
                        actor.userId(),
                        action,
                        action,
                        size,
                        ((long) current - 1) * size);
        return new PageResponse<>(current, size, total, records);
    }

    /**
     * 将审计 JSON 转为结构化节点，保留格式异常的可观测信息。
     *
     * @param value 当前待校验、转换或写入的值。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private JsonNode tree(String value) {
        try {
            return json.readTree(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // e：本次捕获或匹配的异常，用于保留业务分类及失败原因。
            throw new IllegalStateException("Invalid persisted audit summary");
        }
    }
}
