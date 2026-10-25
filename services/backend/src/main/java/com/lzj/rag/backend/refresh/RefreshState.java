package com.lzj.rag.backend.refresh;

import com.lzj.rag.backend.ingestion.IngestionContext;
import com.lzj.rag.backend.knowledgebase.KnowledgeBaseException;
import com.lzj.rag.backend.storage.StorageProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.*;
import java.util.*;

/** Publication and lifecycle hooks use the caller's short base/document transaction. No external I/O. */
@Service @Transactional(propagation=Propagation.MANDATORY)
public class RefreshState {
    private final JdbcTemplate jdbc;private final StorageProperties storage;
    public RefreshState(JdbcTemplate jdbc,StorageProperties storage){this.jdbc=jdbc;this.storage=storage;}
    public void ensure(UUID document){jdbc.update("INSERT INTO kb_refresh_schedule(document_id) VALUES (?) ON CONFLICT DO NOTHING",document);}
    public Map<String,Object> requireClaim(UUID run,long token,String owner) {
        var rows=jdbc.queryForList("""
                SELECT r.* FROM kb_refresh_run r JOIN kb_refresh_schedule s ON s.document_id=r.document_id
                JOIN kb_document d ON d.id=r.document_id JOIN kb_knowledge_base b ON b.id=d.kb_id
                WHERE r.id=? AND r.claim_token=? AND r.lease_owner=? AND r.status IN ('RUNNING','WAITING_INGESTION')
                AND r.lease_until>clock_timestamp() AND r.deadline>clock_timestamp()
                AND s.claim_token=r.claim_token AND s.lease_owner=r.lease_owner AND s.lease_until>clock_timestamp()
                AND s.version=r.schedule_version AND d.version=r.document_version AND d.config_revision=r.config_revision
                AND d.enabled AND d.deleted_at IS NULL AND b.deleted_at IS NULL
                """,run,token,owner);
        if(rows.isEmpty())throw new KnowledgeBaseException(409,"KB_REFRESH_CLAIM_LOST","Refresh lease, document or configuration changed");
        return rows.get(0);
    }
    public void beforePublish(IngestionContext context) {
        var jobs=jdbc.queryForList("SELECT refresh_run_id,refresh_claim_token FROM kb_ingestion_job WHERE id=?",context.jobId());
        var job=jobs.get(0);if(job.get("refresh_run_id")==null)return;
        var row=jdbc.queryForMap("SELECT * FROM kb_refresh_run WHERE id=? FOR UPDATE",job.get("refresh_run_id"));
        var run=requireClaim((UUID)row.get("id"),((Number)job.get("refresh_claim_token")).longValue(),(String)row.get("lease_owner"));
        if(!context.jobId().equals(run.get("job_id")) || !"WAITING_INGESTION".equals(run.get("status")))throw new KnowledgeBaseException(409,"KB_REFRESH_CLAIM_LOST","Refresh no longer owns this ingestion job");
    }
    public void published(IngestionContext context) {
        var ids=jdbc.queryForList("SELECT refresh_run_id FROM kb_ingestion_job WHERE id=? AND refresh_run_id IS NOT NULL",UUID.class,context.jobId());
        if(ids.isEmpty()) {
            var imports=jdbc.queryForList("SELECT * FROM kb_url_import WHERE document_id=? AND status='COMPLETED' AND object_key=?",context.documentId(),context.objectKey());
            if(!imports.isEmpty()) {ensure(context.documentId());var source=imports.get(0);jdbc.update("UPDATE kb_refresh_schedule SET last_content_hash=?,last_etag=?,last_modified=?,last_status='SUCCESS',last_reason='INITIAL_IMPORT_ACTIVATED' WHERE document_id=? AND last_content_hash IS NULL",context.contentHash(),source.get("etag"),source.get("last_modified"),context.documentId());}
            return;
        }
        var run=jdbc.queryForMap("SELECT * FROM kb_refresh_run WHERE id=? FOR UPDATE",ids.get(0));
        requireClaim(ids.get(0),((Number)run.get("claim_token")).longValue(),(String)run.get("lease_owner"));
        String oldKey=jdbc.queryForObject("SELECT object_key FROM kb_document WHERE id=?",String.class,context.documentId());
        jdbc.update("""
                UPDATE kb_document SET object_key=?,original_filename=?,mime_type=?,file_size=?,content_hash=?,version=version+1 WHERE id=?
                """,run.get("candidate_object_key"),run.get("candidate_filename"),run.get("candidate_mime"),run.get("candidate_size"),run.get("candidate_hash"),context.documentId());
        jdbc.update("UPDATE kb_refresh_schedule SET last_content_hash=?,last_etag=?,last_modified=? WHERE document_id=?",run.get("candidate_hash"),run.get("candidate_etag"),run.get("candidate_last_modified"),context.documentId());
        jdbc.update("UPDATE kb_remote_object_cleanup SET status='RETAINED' WHERE object_key=? AND status='WAITING'",run.get("candidate_object_key"));
        queueObject(context.documentId(),context.kbId(),oldKey,null);
        finish(run,"SUCCESS","CONTENT_ACTIVATED");
    }
    public void reserveObject(Map<String,Object> run,String key) {
        jdbc.update("INSERT INTO kb_remote_object_cleanup(id,document_id,kb_id,object_key,run_id,status,next_run_at) VALUES (?,?,?,?,?,'WAITING',clock_timestamp()+(?*interval '1 millisecond'))",
                UUID.randomUUID(),run.get("document_id"),run.get("kb_id"),key,run.get("id"),storage.apiTimeout().multipliedBy(2).plusSeconds(30).toMillis());
        jdbc.update("UPDATE kb_refresh_run SET candidate_object_key=? WHERE id=?",key,run.get("id"));
    }
    public void queueObject(UUID document,UUID kb,String key,UUID run) {
        if(key==null)return;
        jdbc.update("""
                INSERT INTO kb_remote_object_cleanup(id,document_id,kb_id,object_key,run_id,status,next_run_at)
                VALUES (?,?,?,?,?,'PENDING',clock_timestamp()+(?*interval '1 millisecond'))
                ON CONFLICT(object_key) DO UPDATE SET status=CASE WHEN kb_remote_object_cleanup.status='SUCCEEDED' THEN 'SUCCEEDED' ELSE 'PENDING' END,
                next_run_at=GREATEST(kb_remote_object_cleanup.next_run_at,EXCLUDED.next_run_at)
                """,UUID.randomUUID(),document,kb,key,run,storage.apiTimeout().multipliedBy(2).plusSeconds(30).toMillis());
    }
    public void finish(Map<String,Object> run,String status,String reason) {
        com.lzj.rag.backend.operations.OperationLog.committed((UUID)run.get("kb_id"),(UUID)run.get("document_id"),(UUID)run.get("job_id"),null,"REFRESH",status);
        jdbc.update("""
                UPDATE kb_refresh_run SET status=?,reason=?,error_message=?,completed_at=clock_timestamp(),
                duration_ms=GREATEST(0,(EXTRACT(EPOCH FROM(clock_timestamp()-COALESCE(started_at,created_at)))*1000)::bigint),
                lease_owner=NULL,lease_until=NULL,next_run_at=NULL WHERE id=?
                """,status,reason,"FAILED".equals(status)?"Refresh failed; inspect reason and ingestion stages. Source and credentials omitted.":null,run.get("id"));
        var schedule=jdbc.queryForMap("SELECT s.*,d.enabled AS document_enabled,d.deleted_at FROM kb_refresh_schedule s JOIN kb_document d ON d.id=s.document_id WHERE s.document_id=? FOR UPDATE",run.get("document_id"));
        OffsetDateTime next=Boolean.TRUE.equals(schedule.get("enabled")) && Boolean.TRUE.equals(schedule.get("document_enabled")) && schedule.get("deleted_at")==null
                ?next((String)schedule.get("cron_expression"),(String)schedule.get("timezone"),now()):null;
        jdbc.update("""
                UPDATE kb_refresh_schedule SET last_status=?,last_reason=?,last_run_id=?,next_run_at=?,
                consecutive_failures=CASE WHEN ?='FAILED' THEN consecutive_failures+1 ELSE 0 END,
                lease_owner=NULL,lease_until=NULL,claim_token=claim_token+1,updated_at=clock_timestamp() WHERE document_id=?
                """,status,reason,run.get("id"),next,status,run.get("document_id"));
        if(!"SUCCESS".equals(status))queueObject((UUID)run.get("document_id"),(UUID)run.get("kb_id"),(String)run.get("candidate_object_key"),(UUID)run.get("id"));
    }
    public void stopDocument(UUID document) {
        var schedules=jdbc.queryForList("SELECT * FROM kb_refresh_schedule WHERE document_id=? FOR UPDATE",document);if(schedules.isEmpty())return;
        jdbc.update("UPDATE kb_refresh_schedule SET enabled=FALSE,next_run_at=NULL,version=version+1,claim_token=claim_token+1,lease_owner=NULL,lease_until=NULL WHERE document_id=?",document);
        for(var run:jdbc.queryForList("SELECT * FROM kb_refresh_run WHERE document_id=? AND status IN ('PENDING','RUNNING','WAITING_INGESTION') FOR UPDATE",document))finish(run,"SKIPPED","DOCUMENT_DISABLED_OR_DELETED");
    }
    public OffsetDateTime now(){return jdbc.queryForObject("SELECT clock_timestamp()",OffsetDateTime.class);}
    public static OffsetDateTime next(String cron,String timezone,OffsetDateTime after) {
        try {var result=CronExpression.parse(cron).next(after.atZoneSameInstant(ZoneId.of(timezone)));if(result==null)throw new IllegalArgumentException();return result.toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC);}
        catch(RuntimeException error){throw new KnowledgeBaseException(400,"KB_INVALID_SCHEDULE","Provide a valid Spring six-field cron with a future occurrence and IANA timezone");}
    }
}
