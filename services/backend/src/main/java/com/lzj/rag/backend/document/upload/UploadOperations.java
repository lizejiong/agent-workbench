package com.lzj.rag.backend.document.upload;

import com.lzj.rag.backend.document.service.bo.UploadOperationBO;
import com.lzj.rag.backend.document.service.bo.UploadOperationResultBO;
import com.lzj.rag.backend.document.storage.DocumentStorage;
import com.lzj.rag.backend.infra.storage.config.StorageProperties;
import com.lzj.rag.backend.knowledgebase.exception.KnowledgeBaseException;
import com.lzj.rag.backend.knowledgebase.service.internal.KnowledgeBaseAccess;
import com.lzj.rag.backend.security.model.KnowledgeBasePrincipal;
import com.lzj.rag.framework.convention.page.PageResponse;

import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Supplier;

/** 以持久化操作记录协调对象上传和文档入库，并为失败对象保留补偿入口。 */
@Component
public class UploadOperations {

    /**
     * 一次对象清理尝试持有的数据库租约凭据。
     *
     * @param token 当前任务的租约栅栏令牌或清理凭据。
     * @param objectKey 对象存储中的源文件键。
     */
    private record Claim(UUID token, String objectKey) {}

    /** 执行数据库查询及受条件保护的业务状态更新。 */
    private final JdbcTemplate jdbc;

    /** 供内部流程校验知识库归属和取得行锁的访问契约。 */
    private final KnowledgeBaseAccess bases;

    /** 支持流式读写及幂等删除的源对象存储组件。 */
    private final DocumentStorage storage;

    /** 对象存储连接、文件容量、临时目录及上传补偿配置。 */
    private final StorageProperties properties;

    /** 用于短时状态变更的事务模板。 */
    private final TransactionTemplate tx;

    /**
     * 初始化当前组件，保存业务依赖及本次使用的配置。
     *
     * @param jdbc 执行数据库查询及受条件保护的业务状态更新。
     * @param bases 供内部流程校验知识库归属和取得行锁的访问契约。
     * @param storage 支持流式读写及幂等删除的源对象存储组件。
     * @param properties 对象存储连接、文件容量、临时目录及上传补偿配置。
     * @param manager 创建后台短事务及只读事务的事务管理器。
     */
    public UploadOperations(
            JdbcTemplate jdbc,
            KnowledgeBaseAccess bases,
            DocumentStorage storage,
            StorageProperties properties,
            PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.bases = bases;
        this.storage = storage;
        this.properties = properties;
        tx = new TransactionTemplate(manager);
        tx.setTimeout(10);
    }

    /**
     * 在业务锁内预留文档名称、标识及源对象键。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param kb 本次操作涉及的知识库唯一标识。
     * @param key 对象存储键、配置键或业务幂等键。
     * @param payloadHash 幂等请求参数的稳定摘要。
     * @param name 当前业务资源或策略的公开名称。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public UploadOperationBO reserve(
            KnowledgeBasePrincipal actor, UUID kb, String key, String payloadHash, String name) {
        return tx.execute(
                ignored -> {
                    var base = bases.lockOwned(actor, kb);
                    if (base.getEmbeddingModelId() == null)
                        throw conflict(
                                "KB_MODEL_UNBOUND",
                                "Bind an allowed embedding profile before uploading");
                    var previous =
                            jdbc.queryForList(
                                    "SELECT * FROM kb_upload_operation WHERE kb_id=? AND tenant_id=? AND owner_id=? AND idempotency_key=?",
                                    kb,
                                    actor.tenantId(),
                                    actor.userId(),
                                    key);
                    if (!previous.isEmpty()) {
                        var row = previous.get(0);
                        if (!payloadHash.equals(row.get("payload_hash")))
                            throw conflict(
                                    "KB_IDEMPOTENCY_CONFLICT",
                                    "Idempotency key has a different payload");
                        if (!"COMPLETED".equals(row.get("status")))
                            throw conflict(
                                    "KB_UPLOAD_NOT_COMPLETED",
                                    "Upload is pending or failed; inspect storage operations, clean if needed, and use a new key");
                        return new UploadOperationBO(
                                (UUID) row.get("id"),
                                (String) row.get("object_key"),
                                "COMPLETED",
                                true);
                    }
                    checkName(kb, name, null);
                    UUID id = UUID.randomUUID();
                    // 对象路径只使用服务端生成的标识，租户文本和原始文件名不参与路径拼接。
                    String objectKey = "knowledge-base/" + kb + "/" + id + "/original";
                    jdbc.update(
                            """
                INSERT INTO kb_upload_operation(id,kb_id,tenant_id,owner_id,idempotency_key,payload_hash,object_key,status,lease_until)
                VALUES (?,?,?,?,?,?,?,'UPLOADING',CURRENT_TIMESTAMP + (? * interval '1 millisecond'))
                """,
                            id,
                            kb,
                            actor.tenantId(),
                            actor.userId(),
                            key,
                            payloadHash,
                            objectKey,
                            properties.uploadLease().toMillis());
                    return new UploadOperationBO(id, objectKey, "UPLOADING", false);
                });
    }

    /**
     * 复核上传操作后在同一事务内创建文档并标记上传完成。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param kb 本次操作涉及的知识库唯一标识。
     * @param operation 持久化上传操作、操作标识或审计动作名称。
     * @param insert 在上传完成事务内创建文档记录的回调。
     * @param <T> 当前业务结果或输入元素的类型。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public <T> T complete(
            KnowledgeBasePrincipal actor,
            UUID kb,
            UploadOperationBO operation,
            Supplier<T> insert) {
        return tx.execute(
                ignored -> {
                    bases.lockOwned(actor, kb);
                    var rows =
                            jdbc.queryForList(
                                    "SELECT status, lease_until > CURRENT_TIMESTAMP AS valid FROM kb_upload_operation WHERE id=? FOR UPDATE",
                                    operation.id());
                    if (rows.isEmpty()
                            || !"UPLOADING".equals(rows.get(0).get("status"))
                            || !Boolean.TRUE.equals(rows.get(0).get("valid"))) {
                        throw conflict(
                                "KB_UPLOAD_LEASE_EXPIRED",
                                "Upload lease expired; inspect and clean the storage operation");
                    }
                    T result = insert.get();
                    jdbc.update(
                            "UPDATE kb_upload_operation SET status='COMPLETED', updated_at=CURRENT_TIMESTAMP WHERE id=?",
                            operation.id());
                    return result;
                });
    }

    /**
     * 检查同一知识库内的文档名称是否与现有文档或有效预留冲突。
     *
     * @param kb 本次操作涉及的知识库唯一标识。
     * @param name 当前业务资源或策略的公开名称。
     * @param excluded 名称冲突校验时需要排除的当前记录标识。
     */
    public void checkName(UUID kb, String name, UUID excluded) {
        Boolean duplicate =
                jdbc.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM kb_document WHERE kb_id=? AND lower(name)=lower(?) AND deleted_at IS NULL AND (?::uuid IS NULL OR id<>?))",
                        Boolean.class,
                        kb,
                        name,
                        excluded,
                        excluded);
        if (Boolean.TRUE.equals(duplicate))
            throw conflict(
                    "KB_DOCUMENT_NAME_EXISTS",
                    "A document with this name already exists in this knowledge base");
    }

    /**
     * 对未完成上传发起对象删除补偿，并保留可查询的清理结果。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param kb 本次操作涉及的知识库唯一标识。
     * @param id 当前业务对象的唯一标识。
     */
    public void compensate(KnowledgeBasePrincipal actor, UUID kb, UUID id) {
        try {
            cleanup(actor, kb, id, true);
        } catch (RuntimeException ignored) {
            // 补偿意图已持久化；数据库不可用或提交结果不明确时，不能直接删除可能已被文档引用的对象。
            LoggerFactory.getLogger(getClass())
                    .warn("Upload recovery remains pending operationId={}", id);
        }
    }

    /**
     * 在校验归属后重试失败上传的对象清理。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param kb 本次操作涉及的知识库唯一标识。
     * @param id 当前业务对象的唯一标识。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public UploadOperationResultBO retryCleanup(KnowledgeBasePrincipal actor, UUID kb, UUID id) {
        cleanup(actor, kb, id, false);
        return get(actor, kb, id);
    }

    /**
     * 领取清理租约后删除孤立对象，按执行凭据更新清理状态。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param kb 本次操作涉及的知识库唯一标识。
     * @param id 当前业务对象的唯一标识。
     * @param immediate 是否立即执行清理，忽略常规退避等待。
     */
    private void cleanup(KnowledgeBasePrincipal actor, UUID kb, UUID id, boolean immediate) {
        Claim claim =
                tx.execute(
                        ignored -> {
                            bases.lockOwned(actor, kb);
                            var rows =
                                    jdbc.queryForList(
                                            "SELECT *, lease_until <= CURRENT_TIMESTAMP AS expired FROM kb_upload_operation WHERE id=? AND kb_id=? AND tenant_id=? AND owner_id=? FOR UPDATE",
                                            id,
                                            kb,
                                            actor.tenantId(),
                                            actor.userId());
                            if (rows.isEmpty())
                                throw new KnowledgeBaseException(
                                        404,
                                        "KB_OPERATION_NOT_FOUND",
                                        "Storage operation not found");
                            var row = rows.get(0);
                            String status = (String) row.get("status");
                            if ("COMPLETED".equals(status)
                                    || Boolean.TRUE.equals(
                                            jdbc.queryForObject(
                                                    "SELECT EXISTS(SELECT 1 FROM kb_document WHERE object_key=?)",
                                                    Boolean.class,
                                                    row.get("object_key")))) {
                                if (immediate) return null;
                                throw conflict(
                                        "KB_OBJECT_REFERENCED",
                                        "A committed document references this object; cleanup is forbidden");
                            }
                            if ("CLEANED".equals(status)) return null;
                            if (!immediate && !Boolean.TRUE.equals(row.get("expired")))
                                throw conflict(
                                        "KB_OPERATION_BUSY",
                                        "Storage operation has an active lease");
                            UUID token = UUID.randomUUID();
                            jdbc.update(
                                    """
                UPDATE kb_upload_operation SET status='CLEANUP_PENDING', claim_token=?,
                lease_until=CURRENT_TIMESTAMP + (? * interval '1 millisecond'),updated_at=CURRENT_TIMESTAMP WHERE id=?
                """,
                                    token,
                                    properties.apiTimeout().plusSeconds(30).toMillis(),
                                    id);
                            return new Claim(token, (String) row.get("object_key"));
                        });
        if (claim == null) return;
        try {
            storage.delete(claim.objectKey());
        } catch (RuntimeException error) {
            jdbc.update(
                    """
                UPDATE kb_upload_operation SET attempts=attempts+1,error_code='KB_STORAGE_DELETE_FAILED',
                lease_until=CURRENT_TIMESTAMP,next_run_at=CURRENT_TIMESTAMP + interval '30 seconds',updated_at=CURRENT_TIMESTAMP
                WHERE id=? AND claim_token=? AND status='CLEANUP_PENDING'
                """,
                    id,
                    claim.token());
            throw new KnowledgeBaseException(
                    503,
                    "KB_CLEANUP_PENDING",
                    "Object cleanup failed; retry this storage operation later");
        }
        jdbc.update(
                """
            UPDATE kb_upload_operation SET status='CLEANED',attempts=attempts+1,error_code=NULL,next_run_at=NULL,
            lease_until=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE id=? AND claim_token=? AND status='CLEANUP_PENDING'
            """,
                id,
                claim.token());
    }

    /**
     * 分页查询当前用户在指定知识库中的上传与补偿操作。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param kb 本次操作涉及的知识库唯一标识。
     * @param current 当前分页页码，从一开始。
     * @param size 分页容量或当前数据的字节数。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public PageResponse<UploadOperationResultBO> list(
            KnowledgeBasePrincipal actor, UUID kb, int current, int size) {
        bases.requireOwned(actor, kb);
        Long total =
                jdbc.queryForObject(
                        "SELECT count(*) FROM kb_upload_operation WHERE kb_id=? AND tenant_id=? AND owner_id=?",
                        Long.class,
                        kb,
                        actor.tenantId(),
                        actor.userId());
        var records =
                jdbc.query(
                        "SELECT * FROM kb_upload_operation WHERE kb_id=? AND tenant_id=? AND owner_id=? ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
                        (rs, n) ->
                                new UploadOperationResultBO(
                                        rs.getObject("id", UUID.class),
                                        rs.getString("idempotency_key"),
                                        rs.getString("status"),
                                        rs.getInt("attempts"),
                                        rs.getObject("next_run_at", OffsetDateTime.class),
                                        rs.getObject("lease_until", OffsetDateTime.class),
                                        rs.getString("error_code"),
                                        rs.getObject("updated_at", OffsetDateTime.class)),
                        kb,
                        actor.tenantId(),
                        actor.userId(),
                        size,
                        ((long) current - 1) * size);
        return new PageResponse<>(current, size, total, records);
    }

    /**
     * 读取当前用户指定的上传操作详情。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param kb 本次操作涉及的知识库唯一标识。
     * @param id 当前业务对象的唯一标识。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private UploadOperationResultBO get(KnowledgeBasePrincipal actor, UUID kb, UUID id) {
        bases.requireOwned(actor, kb);
        return jdbc.queryForObject(
                "SELECT * FROM kb_upload_operation WHERE id=? AND kb_id=? AND tenant_id=? AND owner_id=?",
                (rs, n) ->
                        new UploadOperationResultBO(
                                rs.getObject("id", UUID.class),
                                rs.getString("idempotency_key"),
                                rs.getString("status"),
                                rs.getInt("attempts"),
                                rs.getObject("next_run_at", OffsetDateTime.class),
                                rs.getObject("lease_until", OffsetDateTime.class),
                                rs.getString("error_code"),
                                rs.getObject("updated_at", OffsetDateTime.class)),
                id,
                kb,
                actor.tenantId(),
                actor.userId());
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
