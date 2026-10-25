package com.lzj.rag.backend.document;

import com.lzj.rag.backend.chunk.*;
import com.lzj.rag.backend.ingestion.IngestionWorkerProperties;
import com.lzj.rag.backend.knowledgebase.*;
import com.lzj.rag.backend.security.KnowledgeBasePrincipal;
import com.lzj.rag.backend.storage.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;

/** Tombstone and intent commit together; object I/O is outside database transactions and is idempotent. */
@Service @Transactional(propagation=Propagation.NOT_SUPPORTED)
public class DocumentCleanupService {
    public record View(UUID id,UUID documentId,String status,String stage,int attempts,int maxAttempts,
            OffsetDateTime nextRunAt,OffsetDateTime leaseUntil,String errorCode,String errorMessage,OffsetDateTime createdAt,OffsetDateTime completedAt) { }
    private final JdbcTemplate jdbc;private final KnowledgeBaseService bases;private final DocumentService documents;
    private final DocumentMapper mapper;private final ChunkMaintenanceService maintenance;private final ObjectStorageClient storage;
    private final MaintenanceProperties config;private final IngestionWorkerProperties worker;private final TransactionTemplate tx;
    private final String instance="document-gc-"+UUID.randomUUID();
    private final com.lzj.rag.backend.refresh.RefreshState refresh;
    private static final String SAFE_ERROR="Cleanup incomplete; repair storage/database access and retry. Source and credentials are omitted.";
    public DocumentCleanupService(JdbcTemplate jdbc,KnowledgeBaseService bases,DocumentService documents,DocumentMapper mapper,
            ChunkMaintenanceService maintenance,ObjectStorageClient storage,MaintenanceProperties config,IngestionWorkerProperties worker,
            StorageProperties storageConfig,PlatformTransactionManager transactions,com.lzj.rag.backend.refresh.RefreshState refresh) {
        this.jdbc=jdbc;this.bases=bases;this.documents=documents;this.mapper=mapper;this.maintenance=maintenance;this.storage=storage;
        this.config=config;this.worker=worker;tx=new TransactionTemplate(transactions);tx.setTimeout(30);
        this.refresh=refresh;
        if(config.cleanupLease().compareTo(storageConfig.apiTimeout().multipliedBy(2).plusSeconds(10))<0)
            throw new IllegalArgumentException("Document cleanup lease must exceed two bounded object API timeouts by at least 10s");
    }
    public View delete(KnowledgeBasePrincipal actor,UUID id,long version) {
        return tx.execute(status->{
            var rows=jdbc.queryForList("SELECT kb_id,deleted_at FROM kb_document WHERE id=? AND tenant_id=? AND owner_id=?",id,actor.tenantId(),actor.userId());
            if(rows.isEmpty())throw notFound();var row=rows.get(0);
            if(row.get("deleted_at")!=null)return ownedByDocument(actor,id);
            bases.lockOwned(actor,(UUID)row.get("kb_id"));jdbc.queryForObject("SELECT id FROM kb_document WHERE id=? FOR UPDATE",UUID.class,id);
            var doc=mapper.selectById(id);
            if(doc.getDeletedAt()!=null)return ownedByDocument(actor,id);
            if(doc.getVersion()!=version)throw new KnowledgeBaseException(409,"KB_VERSION_CONFLICT","Reload the current document version and retry");
            var before=maintenance.summary(doc);maintenance.cancelActive(actor,id);
            refresh.stopDocument(id);
            doc.setEnabled(false);doc.setDeletedAt(OffsetDateTime.now(ZoneOffset.UTC));doc.setUpdatedAt(doc.getDeletedAt());
            doc.setUpdatedBy(actor.userId());doc.setVersion(doc.getVersion()+1);doc.setChunkCount(0L);
            // updateById omits nulls: clear the active generation explicitly in the same transaction.
            mapper.updateById(doc);jdbc.update("UPDATE kb_document SET active_generation=NULL WHERE id=?",id);
            UUID operation=UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO kb_document_cleanup(id,document_id,kb_id,tenant_id,owner_id,object_key,max_attempts)
                    VALUES (?,?,?,?,?,?,?)
                    """,operation,id,doc.getKbId(),actor.tenantId(),actor.userId(),doc.getObjectKey(),worker.cleanupMaxAttempts());
            maintenance.audit(actor,doc,"DOCUMENT_DELETE",List.of(),before,maintenance.summary(doc));return get(actor,operation);
        });
    }
    public View get(KnowledgeBasePrincipal actor,UUID id) {
        var rows=jdbc.query("SELECT * FROM kb_document_cleanup WHERE id=? AND tenant_id=? AND owner_id=?",(rs,n)->view(rs),id,actor.tenantId(),actor.userId());
        if(rows.isEmpty())throw notFound();return rows.get(0);
    }
    private View ownedByDocument(KnowledgeBasePrincipal actor,UUID id) {
        var ids=jdbc.queryForList("SELECT id FROM kb_document_cleanup WHERE document_id=? AND tenant_id=? AND owner_id=?",UUID.class,id,actor.tenantId(),actor.userId());
        if(ids.isEmpty())throw notFound();return get(actor,ids.get(0));
    }
    public View retry(KnowledgeBasePrincipal actor,UUID id) {
        get(actor,id);
        return tx.execute(status->{
            lock(id);var row=jdbc.queryForMap("SELECT *,lease_until>clock_timestamp() AS valid FROM kb_document_cleanup WHERE id=? FOR UPDATE",id);
            if("RUNNING".equals(row.get("status")))throw new KnowledgeBaseException(409,"KB_CLEANUP_ACTIVE","Cleanup is running or awaits expired-lease recovery");
            if("FAILED".equals(row.get("status")))jdbc.update("""
                    UPDATE kb_document_cleanup SET status='PENDING',attempts=0,next_run_at=clock_timestamp(),completed_at=NULL,
                    error_code=NULL,error_message=NULL,claim_token=claim_token+1 WHERE id=?
                    """,id);
            return get(actor,id);
        });
    }
    @Scheduled(fixedDelayString="${kb.worker.document-cleanup-poll-delay:10000}",initialDelayString="${kb.worker.initial-delay:1000}")
    public void scan() {
        if(!worker.enabled())return;
        try { runOnce(); }
        catch(RuntimeException error){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Document cleanup paused; durable intent remains recoverable");}
    }
    /** One bounded claim per invocation; safe to invoke concurrently or after a process restart. */
    public boolean runOnce() {
        var claim=tx.execute(status->{
            var candidates=jdbc.queryForList("""
                    SELECT id,kb_id,document_id FROM kb_document_cleanup WHERE
                    (status='PENDING' AND next_run_at<=clock_timestamp()) OR (status='RUNNING' AND lease_until<=clock_timestamp())
                    ORDER BY COALESCE(next_run_at,lease_until),id LIMIT 32
                    """);
            for(var c:candidates) {
                if(jdbc.queryForList("SELECT id FROM kb_knowledge_base WHERE id=? FOR UPDATE SKIP LOCKED",UUID.class,c.get("kb_id")).isEmpty())continue;
                if(jdbc.queryForList("SELECT id FROM kb_document WHERE id=? FOR UPDATE SKIP LOCKED",UUID.class,c.get("document_id")).isEmpty())continue;
                var rows=jdbc.queryForList("""
                        SELECT * FROM kb_document_cleanup WHERE id=? AND
                        ((status='PENDING' AND next_run_at<=clock_timestamp()) OR (status='RUNNING' AND lease_until<=clock_timestamp())) FOR UPDATE SKIP LOCKED
                        """,c.get("id"));
                if(rows.isEmpty())continue;var row=rows.get(0);
                if(((Number)row.get("attempts")).intValue()>=((Number)row.get("max_attempts")).intValue()) {
                    jdbc.update("UPDATE kb_document_cleanup SET status='FAILED',lease_owner=NULL,lease_until=NULL,next_run_at=NULL,completed_at=clock_timestamp(),claim_token=claim_token+1,error_code='KB_CLEANUP_EXPIRED',error_message=? WHERE id=?",SAFE_ERROR,c.get("id"));continue;
                }
                return jdbc.queryForMap("""
                        UPDATE kb_document_cleanup SET status='RUNNING',attempts=attempts+1,claim_token=claim_token+1,lease_owner=?,
                        lease_until=clock_timestamp()+(?*interval '1 millisecond'),next_run_at=NULL WHERE id=? RETURNING *
                        """,instance,config.cleanupLease().toMillis(),c.get("id"));
            }return null;
        });
        if(claim==null)return false;
        try {
            boolean ready=Boolean.TRUE.equals(tx.execute(status->{
                if(!ownedClaim(claim))return false;
                if("DATABASE".equals(claim.get("stage"))) {
                    UUID doc=(UUID)claim.get("document_id");
                    if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM kb_document WHERE id=?",Boolean.class,doc)))
                        throw new IllegalStateException("Cleanup requires document tombstone");
                    jdbc.update("DELETE FROM kb_chunk WHERE document_id=?",doc); // vectors cascade in this database
                    jdbc.update("DELETE FROM kb_generation WHERE document_id=?",doc);
                    jdbc.update("UPDATE kb_generation_cleanup SET status='SUCCEEDED',completed_at=clock_timestamp(),error_code=NULL WHERE document_id=?",doc);
                    jdbc.update("UPDATE kb_document_cleanup SET stage='OBJECT' WHERE id=?",claim.get("id"));
                }return true;
            }));
            if(!ready)return true;
            storage.delete((String)claim.get("object_key")); // no transaction; unique stable key is never reused
            tx.executeWithoutResult(status->{
                if(!ownedClaim(claim))return;
                jdbc.update("UPDATE kb_document_cleanup SET status='SUCCEEDED',completed_at=clock_timestamp(),lease_owner=NULL,lease_until=NULL,next_run_at=NULL,error_code=NULL,error_message=NULL WHERE id=?",claim.get("id"));
                jdbc.update("UPDATE kb_upload_operation SET status='CLEANED',updated_at=clock_timestamp() WHERE id=? AND status='COMPLETED'",claim.get("document_id"));
            });
        }catch(RuntimeException error) {
            // If the DB itself is unavailable, the persisted lease expires and another instance recovers it.
            tx.executeWithoutResult(status->{
                if(!ownedClaim(claim))return;
                boolean exhausted=((Number)claim.get("attempts")).intValue()>=((Number)claim.get("max_attempts")).intValue();
                jdbc.update("""
                        UPDATE kb_document_cleanup SET status=?,lease_owner=NULL,lease_until=NULL,claim_token=claim_token+1,
                        next_run_at=CASE WHEN ? THEN NULL ELSE clock_timestamp()+(?*interval '1 millisecond') END,
                        completed_at=CASE WHEN ? THEN clock_timestamp() ELSE NULL END,error_code=?,error_message=? WHERE id=?
                        """,exhausted?"FAILED":"PENDING",exhausted,worker.backoffMillis(((Number)claim.get("attempts")).intValue()),exhausted,
                        error instanceof KnowledgeBaseException kb?kb.code():"KB_CLEANUP_FAILED",SAFE_ERROR,claim.get("id"));
            });
        }return true;
    }
    private void lock(UUID id) {
        var row=jdbc.queryForMap("SELECT kb_id,document_id FROM kb_document_cleanup WHERE id=?",id);
        jdbc.queryForObject("SELECT id FROM kb_knowledge_base WHERE id=? FOR UPDATE",UUID.class,row.get("kb_id"));
        jdbc.queryForObject("SELECT id FROM kb_document WHERE id=? FOR UPDATE",UUID.class,row.get("document_id"));
    }
    private boolean ownedClaim(Map<String,Object> claim) {
        lock((UUID)claim.get("id"));
        return !jdbc.queryForList("SELECT id FROM kb_document_cleanup WHERE id=? AND status='RUNNING' AND claim_token=? AND lease_owner=? AND lease_until>clock_timestamp() FOR UPDATE",
                claim.get("id"),claim.get("claim_token"),instance).isEmpty();
    }
    private View view(java.sql.ResultSet rs)throws java.sql.SQLException {
        return new View(rs.getObject("id",UUID.class),rs.getObject("document_id",UUID.class),rs.getString("status"),rs.getString("stage"),rs.getInt("attempts"),rs.getInt("max_attempts"),
                rs.getObject("next_run_at",OffsetDateTime.class),rs.getObject("lease_until",OffsetDateTime.class),rs.getString("error_code"),rs.getString("error_message"),rs.getObject("created_at",OffsetDateTime.class),rs.getObject("completed_at",OffsetDateTime.class));
    }
    private static KnowledgeBaseException notFound(){return new KnowledgeBaseException(404,"KB_DOCUMENT_NOT_FOUND","Document or cleanup operation not found");}
}
