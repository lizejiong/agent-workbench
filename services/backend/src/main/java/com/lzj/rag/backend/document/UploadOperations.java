package com.lzj.rag.backend.document;

import com.lzj.rag.backend.knowledgebase.*;
import com.lzj.rag.backend.security.KnowledgeBasePrincipal;
import com.lzj.rag.backend.storage.*;
import com.lzj.rag.framework.convention.page.PageResponse;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Supplier;

/** Durable intent before PUT; all transactions are short and contain no storage I/O. */
@Component
public class UploadOperations {
    public record Operation(UUID id, String objectKey, String status, boolean replayed) { }
    public record View(UUID id, String idempotencyKey, String status, int attempts, OffsetDateTime nextRunAt,
                       OffsetDateTime leaseUntil, String errorCode, OffsetDateTime updatedAt) { }
    private record Claim(UUID token, String objectKey) { }
    private final JdbcTemplate jdbc;
    private final KnowledgeBaseService bases;
    private final ObjectStorageClient storage;
    private final StorageProperties properties;
    private final TransactionTemplate tx;

    public UploadOperations(JdbcTemplate jdbc, KnowledgeBaseService bases, ObjectStorageClient storage,
                            StorageProperties properties, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.bases = bases; this.storage = storage; this.properties = properties;
        tx = new TransactionTemplate(manager); tx.setTimeout(10);
    }

    Operation reserve(KnowledgeBasePrincipal actor, UUID kb, String key, String payloadHash, String name) {
        return tx.execute(ignored -> {
            var base = bases.lockOwned(actor, kb);
            if (base.getEmbeddingModelId() == null) throw conflict("KB_MODEL_UNBOUND", "Bind an allowed embedding profile before uploading");
            var previous = jdbc.queryForList("SELECT * FROM kb_upload_operation WHERE kb_id=? AND tenant_id=? AND owner_id=? AND idempotency_key=?",
                    kb, actor.tenantId(), actor.userId(), key);
            if (!previous.isEmpty()) {
                var row = previous.get(0);
                if (!payloadHash.equals(row.get("payload_hash"))) throw conflict("KB_IDEMPOTENCY_CONFLICT", "Idempotency key has a different payload");
                if (!"COMPLETED".equals(row.get("status"))) throw conflict("KB_UPLOAD_NOT_COMPLETED", "Upload is pending or failed; inspect storage operations, clean if needed, and use a new key");
                return new Operation((UUID) row.get("id"), (String) row.get("object_key"), "COMPLETED", true);
            }
            checkName(kb, name, null);
            UUID id = UUID.randomUUID();
            // No tenant strings or untrusted original file name become a filesystem/object path.
            String objectKey = "knowledge-base/" + kb + "/" + id + "/original";
            jdbc.update("""
                    INSERT INTO kb_upload_operation(id,kb_id,tenant_id,owner_id,idempotency_key,payload_hash,object_key,status,lease_until)
                    VALUES (?,?,?,?,?,?,?,'UPLOADING',CURRENT_TIMESTAMP + (? * interval '1 millisecond'))
                    """, id, kb, actor.tenantId(), actor.userId(), key, payloadHash, objectKey, properties.uploadLease().toMillis());
            return new Operation(id, objectKey, "UPLOADING", false);
        });
    }

    <T> T complete(KnowledgeBasePrincipal actor, UUID kb, Operation operation, Supplier<T> insert) {
        return tx.execute(ignored -> {
            bases.lockOwned(actor, kb);
            var rows = jdbc.queryForList("SELECT status, lease_until > CURRENT_TIMESTAMP AS valid FROM kb_upload_operation WHERE id=? FOR UPDATE", operation.id());
            if (rows.isEmpty() || !"UPLOADING".equals(rows.get(0).get("status")) || !Boolean.TRUE.equals(rows.get(0).get("valid"))) {
                throw conflict("KB_UPLOAD_LEASE_EXPIRED", "Upload lease expired; inspect and clean the storage operation");
            }
            T result = insert.get();
            jdbc.update("UPDATE kb_upload_operation SET status='COMPLETED', updated_at=CURRENT_TIMESTAMP WHERE id=?", operation.id());
            return result;
        });
    }

    void checkName(UUID kb, String name, UUID excluded) {
        Boolean duplicate = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM kb_document WHERE kb_id=? AND lower(name)=lower(?) AND deleted_at IS NULL AND (?::uuid IS NULL OR id<>?))",
                Boolean.class, kb, name, excluded, excluded);
        if (Boolean.TRUE.equals(duplicate)) throw conflict("KB_DOCUMENT_NAME_EXISTS", "A document with this name already exists in this knowledge base");
    }

    void compensate(KnowledgeBasePrincipal actor, UUID kb, UUID id) {
        try { cleanup(actor, kb, id, true); }
        catch (RuntimeException ignored) {
            // Intent survives even when the database is down. Never blindly delete after an ambiguous commit.
            LoggerFactory.getLogger(getClass()).warn("Upload recovery remains pending operationId={}", id);
        }
    }

    public View retryCleanup(KnowledgeBasePrincipal actor, UUID kb, UUID id) {
        cleanup(actor, kb, id, false);
        return get(actor, kb, id);
    }

    private void cleanup(KnowledgeBasePrincipal actor, UUID kb, UUID id, boolean immediate) {
        Claim claim = tx.execute(ignored -> {
            bases.lockOwned(actor, kb);
            var rows = jdbc.queryForList("SELECT *, lease_until <= CURRENT_TIMESTAMP AS expired FROM kb_upload_operation WHERE id=? AND kb_id=? AND tenant_id=? AND owner_id=? FOR UPDATE",
                    id, kb, actor.tenantId(), actor.userId());
            if (rows.isEmpty()) throw new KnowledgeBaseException(404, "KB_OPERATION_NOT_FOUND", "Storage operation not found");
            var row = rows.get(0);
            String status = (String) row.get("status");
            if ("COMPLETED".equals(status) || Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM kb_document WHERE object_key=?)", Boolean.class, row.get("object_key")))) {
                if (immediate) return null;
                throw conflict("KB_OBJECT_REFERENCED", "A committed document references this object; cleanup is forbidden");
            }
            if ("CLEANED".equals(status)) return null;
            if (!immediate && !Boolean.TRUE.equals(row.get("expired"))) throw conflict("KB_OPERATION_BUSY", "Storage operation has an active lease");
            UUID token = UUID.randomUUID();
            jdbc.update("""
                    UPDATE kb_upload_operation SET status='CLEANUP_PENDING', claim_token=?,
                    lease_until=CURRENT_TIMESTAMP + (? * interval '1 millisecond'),updated_at=CURRENT_TIMESTAMP WHERE id=?
                    """, token, properties.apiTimeout().plusSeconds(30).toMillis(), id);
            return new Claim(token, (String) row.get("object_key"));
        });
        if (claim == null) return;
        try {
            storage.delete(claim.objectKey());
        } catch (RuntimeException error) {
            jdbc.update("""
                    UPDATE kb_upload_operation SET attempts=attempts+1,error_code='KB_STORAGE_DELETE_FAILED',
                    lease_until=CURRENT_TIMESTAMP,next_run_at=CURRENT_TIMESTAMP + interval '30 seconds',updated_at=CURRENT_TIMESTAMP
                    WHERE id=? AND claim_token=? AND status='CLEANUP_PENDING'
                    """, id, claim.token());
            throw new KnowledgeBaseException(503, "KB_CLEANUP_PENDING", "Object cleanup failed; retry this storage operation later");
        }
        jdbc.update("""
                UPDATE kb_upload_operation SET status='CLEANED',attempts=attempts+1,error_code=NULL,next_run_at=NULL,
                lease_until=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE id=? AND claim_token=? AND status='CLEANUP_PENDING'
                """, id, claim.token());
    }

    public PageResponse<View> list(KnowledgeBasePrincipal actor, UUID kb, int current, int size) {
        bases.requireOwned(actor, kb);
        Long total = jdbc.queryForObject("SELECT count(*) FROM kb_upload_operation WHERE kb_id=? AND tenant_id=? AND owner_id=?",
                Long.class, kb, actor.tenantId(), actor.userId());
        var records = jdbc.query("SELECT * FROM kb_upload_operation WHERE kb_id=? AND tenant_id=? AND owner_id=? ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
                (rs, n) -> new View(rs.getObject("id", UUID.class), rs.getString("idempotency_key"), rs.getString("status"), rs.getInt("attempts"),
                        rs.getObject("next_run_at", OffsetDateTime.class), rs.getObject("lease_until", OffsetDateTime.class),
                        rs.getString("error_code"), rs.getObject("updated_at", OffsetDateTime.class)),
                kb, actor.tenantId(), actor.userId(), size, ((long) current - 1) * size);
        return new PageResponse<>(current, size, total, records);
    }
    private View get(KnowledgeBasePrincipal actor, UUID kb, UUID id) {
        bases.requireOwned(actor, kb);
        return jdbc.queryForObject("SELECT * FROM kb_upload_operation WHERE id=? AND kb_id=? AND tenant_id=? AND owner_id=?",
                (rs, n) -> new View(rs.getObject("id", UUID.class), rs.getString("idempotency_key"), rs.getString("status"), rs.getInt("attempts"),
                        rs.getObject("next_run_at", OffsetDateTime.class), rs.getObject("lease_until", OffsetDateTime.class),
                        rs.getString("error_code"), rs.getObject("updated_at", OffsetDateTime.class)), id, kb, actor.tenantId(), actor.userId());
    }
    private static KnowledgeBaseException conflict(String code, String message) { return new KnowledgeBaseException(409, code, message); }
}
