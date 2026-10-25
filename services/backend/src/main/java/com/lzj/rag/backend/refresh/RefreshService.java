package com.lzj.rag.backend.refresh;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lzj.rag.backend.document.*;
import com.lzj.rag.backend.ingestion.*;
import com.lzj.rag.backend.knowledgebase.*;
import com.lzj.rag.backend.parser.ParserRegistry;
import com.lzj.rag.backend.security.KnowledgeBasePrincipal;
import com.lzj.rag.backend.storage.*;
import com.lzj.rag.framework.convention.page.PageResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import static com.lzj.rag.backend.refresh.RemoteTypes.*;

/** Durable download handoff, not an in-memory queue. Model work remains in IngestionWorker. */
@Service
public class RefreshService {
    public record Claim(UUID id,UUID documentId,UUID kbId,long token,String owner) { }
    private final JdbcTemplate jdbc;private final DocumentService documents;private final KnowledgeBaseService bases;
    private final IngestionJobService jobs;private final RefreshState state;private final RemoteDownloader downloader;
    private final ObjectStorageClient storage;private final StorageProperties storageConfig;private final RemoteProperties config;
    private final ParserRegistry parsers;private final ObjectMapper json;private final TransactionTemplate tx;
    private final String instance="refresh-"+UUID.randomUUID();
    public RefreshService(JdbcTemplate jdbc,DocumentService documents,KnowledgeBaseService bases,IngestionJobService jobs,RefreshState state,
            RemoteDownloader downloader,ObjectStorageClient storage,StorageProperties storageConfig,RemoteProperties config,ParserRegistry parsers,ObjectMapper json,PlatformTransactionManager transactions) {
        this.jdbc=jdbc;this.documents=documents;this.bases=bases;this.jobs=jobs;this.state=state;this.downloader=downloader;this.storage=storage;this.storageConfig=storageConfig;this.config=config;this.parsers=parsers;this.json=json;
        tx=new TransactionTemplate(transactions);tx.setTimeout(10);
    }
    @Transactional(readOnly=true,timeout=10)
    public ScheduleView schedule(KnowledgeBasePrincipal actor,UUID document) {requireUrl(actor,document);return scheduleView(document);}
    @Transactional(timeout=10)
    public ScheduleView configure(KnowledgeBasePrincipal actor,UUID document,ScheduleRequest request) {
        var doc=lock(actor,document);var current=jdbc.queryForMap("SELECT * FROM kb_refresh_schedule WHERE document_id=? FOR UPDATE",document);
        if(((Number)current.get("version")).longValue()!=request.version())throw conflict("KB_VERSION_CONFLICT","Reload the current refresh schedule");
        var next=RefreshState.next(request.cron(),request.timezone(),state.now());
        if(request.enabled()) {
            downloader.validateUrl(doc.getSourceUrl());if(!doc.getEnabled())throw conflict("KB_DOCUMENT_DISABLED","Enable the document before its schedule");
            if(activeRefresh(document))throw conflict("KB_REFRESH_ACTIVE","Wait for active refresh before changing an enabled schedule");
        } else {
            cancelRunJobs(actor,document);
            for(var run:jdbc.queryForList("SELECT * FROM kb_refresh_run WHERE document_id=? AND status IN ('PENDING','RUNNING','WAITING_INGESTION') FOR UPDATE",document))state.finish(run,"SKIPPED","SCHEDULE_DISABLED");
        }
        jdbc.update("UPDATE kb_refresh_schedule SET enabled=?,cron_expression=?,timezone=?,version=version+1,next_run_at=?,claim_token=claim_token+1,updated_at=clock_timestamp() WHERE document_id=?",request.enabled(),request.cron(),request.timezone(),request.enabled()?next:null,document);
        return scheduleView(document);
    }
    @Transactional(timeout=10)
    public Submitted submit(KnowledgeBasePrincipal actor,UUID document,RefreshRequest request) {
        // Same idempotency locking convention as ingestion, in its own command namespace.
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,"refresh/"+actor.tenantId()+"/"+actor.userId()+"/"+request.requestId());
        requireUrl(actor,document);
        var replay=jdbc.queryForList("SELECT * FROM kb_refresh_run WHERE tenant_id=? AND owner_id=? AND request_id=?",actor.tenantId(),actor.userId(),request.requestId());
        if(!replay.isEmpty()) {
            var row=replay.get(0);if(!document.equals(row.get("document_id")) || request.confirmOverwriteEdited()!=Boolean.TRUE.equals(row.get("confirm_overwrite_edited")) || !Objects.equals(request.expectedDocumentVersion(),row.get("expected_document_version")))throw conflict("KB_IDEMPOTENCY_CONFLICT","requestId is bound to another refresh request");
            return new Submitted(runView((UUID)row.get("id")),true);
        }
        var doc=lock(actor,document);downloader.validateUrl(doc.getSourceUrl());
        if(!doc.getEnabled())throw conflict("KB_DOCUMENT_DISABLED","Enable the document before refreshing");
        if(activeRefresh(document))throw conflict("KB_REFRESH_ACTIVE","Document already has an active refresh");
        if(activeJob(document))throw conflict("KB_JOB_ACTIVE","Wait for ingestion before refreshing");
        checkManual(doc,request.confirmOverwriteEdited(),request.expectedDocumentVersion());
        UUID id=create(doc,request.requestId(),"MANUAL",request.confirmOverwriteEdited(),request.expectedDocumentVersion());
        return new Submitted(runView(id),false);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,timeout=10)
    public PageResponse<RunView> list(KnowledgeBasePrincipal actor,UUID document,int current,int size) {
        requireUrl(actor,document);long total=jdbc.queryForObject("SELECT count(*) FROM kb_refresh_run WHERE document_id=?",Long.class,document);
        return new PageResponse<>(current,size,total,jdbc.query("SELECT * FROM kb_refresh_run WHERE document_id=? ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",RefreshService::runView,document,size,((long)current-1)*size));
    }
    /** Due schedules become durable runs under the same document lock as manual submissions. */
    public void enqueueDue() {
        tx.executeWithoutResult(status->{
            for(var c:jdbc.queryForList("SELECT s.document_id,d.kb_id FROM kb_refresh_schedule s JOIN kb_document d ON d.id=s.document_id WHERE s.enabled AND s.next_run_at<=clock_timestamp() ORDER BY s.next_run_at,s.document_id LIMIT 32")) {
                if(!tryLocks((UUID)c.get("kb_id"),(UUID)c.get("document_id")))continue;
                UUID document=(UUID)c.get("document_id");var schedules=jdbc.queryForList("SELECT * FROM kb_refresh_schedule WHERE document_id=? AND enabled AND next_run_at<=clock_timestamp() FOR UPDATE SKIP LOCKED",document);if(schedules.isEmpty() || activeRefresh(document))continue;
                var doc=rawDocument(document);UUID id=create(doc,"scheduled-"+UUID.randomUUID(),"SCHEDULED",false,null);
                var run=jdbc.queryForMap("SELECT * FROM kb_refresh_run WHERE id=?",id);
                if(doc.getDeletedAt()!=null || !doc.getEnabled()) {jdbc.update("UPDATE kb_refresh_schedule SET enabled=FALSE WHERE document_id=?",document);state.finish(run,"SKIPPED","DOCUMENT_DISABLED_OR_DELETED");}
                else if(activeJob(document))state.finish(run,"SKIPPED","INGESTION_ACTIVE");
                else if(Boolean.TRUE.equals(doc.getHasManualChanges()))state.finish(run,"SKIPPED","MANUAL_CHANGES_REQUIRE_CONFIRMATION");
            }
        });
    }
    public Claim claimNext() {
        return tx.execute(status->{
            if(jdbc.queryForObject("SELECT count(*) FROM kb_refresh_run WHERE lease_owner=? AND status IN ('RUNNING','WAITING_INGESTION') AND lease_until>clock_timestamp()",Integer.class,instance)>=config.concurrency())return null;
            var candidates=jdbc.queryForList("SELECT id,document_id,kb_id FROM kb_refresh_run WHERE (status='PENDING' AND next_run_at<=clock_timestamp()) OR (status IN ('RUNNING','WAITING_INGESTION') AND lease_until<=clock_timestamp()) ORDER BY COALESCE(next_run_at,lease_until),created_at,id LIMIT 32");
            for(var c:candidates) {
                UUID document=(UUID)c.get("document_id");if(!tryLocks((UUID)c.get("kb_id"),document))continue;
                var rows=jdbc.queryForList("SELECT * FROM kb_refresh_run WHERE id=? AND ((status='PENDING' AND next_run_at<=clock_timestamp()) OR (status IN ('RUNNING','WAITING_INGESTION') AND lease_until<=clock_timestamp())) FOR UPDATE SKIP LOCKED",c.get("id"));if(rows.isEmpty())continue;
                var run=rows.get(0);var doc=rawDocument(document);
                if(!inputsValid(run,doc)){cancelJob(run,doc);state.finish(run,"SKIPPED","INPUT_CHANGED");continue;}
                if(!"PENDING".equals(run.get("status"))) {
                    cancelJob(run,doc);
                    retryOrFinish(run,"KB_REFRESH_LEASE_EXPIRED",true);continue;
                }
                if(activeJob(document)){state.finish(run,"SKIPPED","INGESTION_ACTIVE");continue;}
                if(((Number)run.get("attempts")).intValue()>=config.maxAttempts()) {state.finish(run,"FAILED","KB_REFRESH_ATTEMPTS_EXHAUSTED");continue;}
                long token=jdbc.queryForObject("UPDATE kb_refresh_schedule SET claim_token=claim_token+1,lease_owner=?,lease_until=clock_timestamp()+(?*interval '1 millisecond'),next_run_at=NULL WHERE document_id=? RETURNING claim_token",Long.class,instance,config.lease().toMillis(),document);
                jdbc.update("UPDATE kb_refresh_run SET status='RUNNING',attempts=attempts+1,claim_token=?,lease_owner=?,lease_until=clock_timestamp()+(?*interval '1 millisecond'),deadline=COALESCE(deadline,clock_timestamp()+(?*interval '1 millisecond')),started_at=COALESCE(started_at,clock_timestamp()),execution_started_at=NULL,next_run_at=NULL WHERE id=?",token,instance,config.lease().toMillis(),config.runDeadline().toMillis(),run.get("id"));
                return new Claim((UUID)run.get("id"),document,(UUID)run.get("kb_id"),token,instance);
            }
            return null;
        });
    }
    public void execute(Claim claim) {
        // Repeated delivery of one credential must not alter the legitimate execution or its candidate.
        boolean entered=Boolean.TRUE.equals(tx.execute(status->{lockRaw(claim);return jdbc.update("UPDATE kb_refresh_run SET execution_started_at=clock_timestamp() WHERE id=? AND claim_token=? AND lease_owner=? AND lease_until>clock_timestamp() AND deadline>clock_timestamp() AND status='RUNNING' AND execution_started_at IS NULL",claim.id(),claim.token(),claim.owner())==1;}));
        if(!entered)return;
        try {
            var input=tx.execute(status->{lockRaw(claim);var run=state.requireClaim(claim.id(),claim.token(),claim.owner());var result=new HashMap<>(run);result.putAll(jdbc.queryForMap("SELECT source_url,original_filename,content_hash,active_generation,ingestion_spec FROM kb_document WHERE id=?",claim.documentId()));result.putAll(jdbc.queryForMap("SELECT last_content_hash,last_etag,last_modified FROM kb_refresh_schedule WHERE document_id=?",claim.documentId()));return result;});
            boolean conditional=input.get("active_generation")!=null && Objects.equals(input.get("content_hash"),input.get("last_content_hash"));
            try(var download=downloader.download((String)input.get("source_url"),conditional?(String)input.get("last_etag"):null,conditional?(String)input.get("last_modified"):null,instant(input.get("deadline")))) {
                boolean same=download.httpStatus()==304 || input.get("active_generation")!=null && download.hash().equals(input.get("content_hash"));
                if(same) {
                    tx.executeWithoutResult(status->{lockRaw(claim);var run=state.requireClaim(claim.id(),claim.token(),claim.owner());jdbc.update("UPDATE kb_refresh_run SET http_status=? WHERE id=?",download.httpStatus(),claim.id());
                        // Same bytes already belong to the active version; rotating validators is safe without re-embedding.
                        jdbc.update("UPDATE kb_refresh_schedule SET last_content_hash=?,last_etag=?,last_modified=? WHERE document_id=?",input.get("content_hash"),download.httpStatus()==304 && download.etag()==null?input.get("last_etag"):download.etag(),download.httpStatus()==304 && download.lastModified()==null?input.get("last_modified"):download.lastModified(),claim.documentId());
                        state.finish(run,"SKIPPED",download.httpStatus()==304?"HTTP_NOT_MODIFIED":"CONTENT_HASH_UNCHANGED");});return;
                }
                try(var prepared=PreparedUpload.prepare(download.file((String)input.get("original_filename")),storageConfig)) {
                    var spec=json.readValue(input.get("ingestion_spec").toString(),DocumentTypes.IngestionSpec.class);parsers.resolve(prepared.mime,spec.parser(),prepared.filename,spec.parseProfile());
                    String key="kb/"+claim.kbId()+"/remote/"+UUID.randomUUID()+"/original";
                    tx.executeWithoutResult(status->{lockRaw(claim);var run=state.requireClaim(claim.id(),claim.token(),claim.owner());state.reserveObject(run,key);
                        jdbc.update("UPDATE kb_refresh_run SET candidate_filename=?,candidate_mime=?,candidate_size=?,candidate_hash=?,candidate_etag=?,candidate_last_modified=?,http_status=? WHERE id=?",prepared.filename,prepared.mime,prepared.size,prepared.hash,download.etag(),download.lastModified(),download.httpStatus(),claim.id());});
                    storage.put(key,prepared.path,prepared.mime,prepared.hash);
                    tx.executeWithoutResult(status->{lockRaw(claim);var run=state.requireClaim(claim.id(),claim.token(),claim.owner());jobs.submitRefresh(actor(run),run,claim.token());});
                }
            }
        }catch(Exception error) {
            if(Thread.currentThread().isInterrupted())return; // A dead process/worker leaves the durable lease for recovery.
            tx.executeWithoutResult(status->{lockRaw(claim);var rows=jdbc.queryForList("SELECT * FROM kb_refresh_run WHERE id=? AND claim_token=? AND lease_owner=? AND lease_until>clock_timestamp() AND status='RUNNING' FOR UPDATE",claim.id(),claim.token(),claim.owner());
                if(rows.isEmpty())return;String code=error instanceof KnowledgeBaseException kb?kb.code():"KB_REFRESH_FAILED";
                retryOrFinish(rows.get(0),code,Set.of("KB_REMOTE_TIMEOUT","KB_REMOTE_BUSY","KB_REMOTE_RATE_LIMITED","KB_REMOTE_DOWNLOAD_FAILED","KB_REMOTE_HTTP_ERROR","KB_STORAGE_UNAVAILABLE").contains(code));});
        }
    }
    /** Renew only live claims; never resurrect an expired lease or exceed the run deadline. */
    public void heartbeat() {
        tx.executeWithoutResult(status->{
            for(var c:jdbc.queryForList("SELECT id,kb_id,document_id FROM kb_refresh_run WHERE lease_owner=? AND status IN ('RUNNING','WAITING_INGESTION') ORDER BY id LIMIT 32",instance)) {
                if(!tryLocks((UUID)c.get("kb_id"),(UUID)c.get("document_id")))continue;
                jdbc.update("""
                        UPDATE kb_refresh_schedule s SET lease_until=LEAST(r.deadline,clock_timestamp()+(?*interval '1 millisecond')) FROM kb_refresh_run r
                        WHERE r.id=? AND r.document_id=s.document_id AND r.lease_owner=? AND s.lease_owner=r.lease_owner AND s.claim_token=r.claim_token
                        AND r.status IN ('RUNNING','WAITING_INGESTION') AND r.lease_until>clock_timestamp() AND s.lease_until>clock_timestamp() AND r.deadline>clock_timestamp()
                        """,config.lease().toMillis(),c.get("id"),instance);
                jdbc.update("UPDATE kb_refresh_run r SET lease_until=s.lease_until FROM kb_refresh_schedule s WHERE r.id=? AND s.document_id=r.document_id AND r.lease_owner=? AND s.lease_owner=r.lease_owner AND s.claim_token=r.claim_token AND r.status IN ('RUNNING','WAITING_INGESTION') AND r.lease_until>clock_timestamp() AND r.deadline>clock_timestamp()",c.get("id"),instance);
            }
        });
    }
    public void reconcile() {
        tx.executeWithoutResult(status->{
            for(var c:jdbc.queryForList("SELECT id,document_id,kb_id FROM kb_refresh_run WHERE status IN ('RUNNING','WAITING_INGESTION') ORDER BY started_at,id LIMIT 64")) {
                if(!tryLocks((UUID)c.get("kb_id"),(UUID)c.get("document_id")))continue;
                var rows=jdbc.queryForList("SELECT * FROM kb_refresh_run WHERE id=? AND status IN ('RUNNING','WAITING_INGESTION') FOR UPDATE",c.get("id"));if(rows.isEmpty())continue;
                var run=rows.get(0);var doc=rawDocument((UUID)c.get("document_id"));
                if(!inputsValid(run,doc)){cancelJob(run,doc);state.finish(run,"SKIPPED","INPUT_CHANGED");continue;}
                if(instant(run.get("deadline")).isBefore(state.now().toInstant())){cancelJob(run,doc);state.finish(run,"FAILED","KB_REFRESH_DEADLINE");continue;}
                if(run.get("job_id")!=null) {
                    var job=jdbc.queryForMap("SELECT status,error_code FROM kb_ingestion_job WHERE id=?",run.get("job_id"));
                    if(Set.of("FAILED","CANCELLED").contains(job.get("status")))state.finish(run,"FAILED",Objects.toString(job.get("error_code"),"KB_INGESTION_FAILED"));
                }
            }
            jdbc.update("UPDATE kb_url_import SET status='FAILED',error_code='KB_IMPORT_EXPIRED' WHERE status='RUNNING' AND lease_until<=clock_timestamp()");
        });
    }
    private void retryOrFinish(Map<String,Object> run,String reason,boolean retryable) {
        var doc=rawDocument((UUID)run.get("document_id"));
        if(!inputsValid(run,doc)){state.finish(run,"SKIPPED","INPUT_CHANGED");return;}
        if(!retryable || ((Number)run.get("attempts")).intValue()>=((Number)run.get("max_attempts")).intValue() || run.get("deadline")!=null && !instant(run.get("deadline")).isAfter(state.now().toInstant())) {state.finish(run,"FAILED",reason);return;}
        state.queueObject(doc.getId(),doc.getKbId(),(String)run.get("candidate_object_key"),(UUID)run.get("id"));
        jdbc.update("UPDATE kb_refresh_run SET status='PENDING',reason=?,next_run_at=clock_timestamp()+(?*interval '1 second'),lease_owner=NULL,lease_until=NULL,job_id=NULL,candidate_object_key=NULL WHERE id=?",reason,Math.min(60,2L<<Math.max(0,((Number)run.get("attempts")).intValue()-1)),run.get("id"));
        jdbc.update("UPDATE kb_refresh_schedule SET claim_token=claim_token+1,lease_owner=NULL,lease_until=NULL WHERE document_id=?",run.get("document_id"));
    }
    private UUID create(Document doc,String key,String trigger,boolean confirm,Long expected) {
        state.ensure(doc.getId());long scheduleVersion=jdbc.queryForObject("SELECT version FROM kb_refresh_schedule WHERE document_id=? FOR UPDATE",Long.class,doc.getId());UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO kb_refresh_run(id,document_id,kb_id,tenant_id,owner_id,request_id,trigger_type,status,document_version,config_revision,schedule_version,confirm_overwrite_edited,expected_document_version,max_attempts) VALUES (?,?,?,?,?,?,?,'PENDING',?,?,?,?,?,?)",id,doc.getId(),doc.getKbId(),doc.getTenantId(),doc.getOwnerId(),key,trigger,doc.getVersion(),doc.getConfigRevision(),scheduleVersion,confirm,expected,config.maxAttempts());
        jdbc.update("UPDATE kb_refresh_schedule SET next_run_at=NULL WHERE document_id=?",doc.getId());return id;
    }
    private Document requireUrl(KnowledgeBasePrincipal actor,UUID id){var doc=documents.requireOwned(actor,id);if(!"URL".equals(doc.getSourceType()))throw conflict("KB_NOT_REMOTE_DOCUMENT","Only URL-imported documents can be refreshed");return doc;}
    private Document lock(KnowledgeBasePrincipal actor,UUID id){var doc=requireUrl(actor,id);bases.lockOwned(actor,doc.getKbId());doc=documents.lockOwned(actor,id);state.ensure(id);return doc;}
    private void lockRaw(Claim claim){jdbc.queryForObject("SELECT id FROM kb_knowledge_base WHERE id=? FOR UPDATE",UUID.class,claim.kbId());jdbc.queryForObject("SELECT id FROM kb_document WHERE id=? FOR UPDATE",UUID.class,claim.documentId());}
    private boolean tryLocks(UUID kb,UUID doc){return !jdbc.queryForList("SELECT id FROM kb_knowledge_base WHERE id=? FOR UPDATE SKIP LOCKED",UUID.class,kb).isEmpty() && !jdbc.queryForList("SELECT id FROM kb_document WHERE id=? FOR UPDATE SKIP LOCKED",UUID.class,doc).isEmpty();}
    private Document rawDocument(UUID id){return jdbc.queryForObject("SELECT * FROM kb_document WHERE id=?",(rs,n)->{var d=new Document();d.setId(id);d.setKbId(rs.getObject("kb_id",UUID.class));d.setTenantId(rs.getString("tenant_id"));d.setOwnerId(rs.getString("owner_id"));d.setEnabled(rs.getBoolean("enabled"));d.setDeletedAt(rs.getObject("deleted_at",OffsetDateTime.class));d.setVersion(rs.getLong("version"));d.setConfigRevision(rs.getLong("config_revision"));d.setHasManualChanges(rs.getBoolean("has_manual_changes"));return d;},id);}
    private boolean inputsValid(Map<String,Object> run,Document doc){return doc.getDeletedAt()==null && doc.getEnabled() && doc.getVersion().equals(run.get("document_version")) && doc.getConfigRevision().equals(run.get("config_revision")) && Objects.equals(run.get("schedule_version"),jdbc.queryForObject("SELECT version FROM kb_refresh_schedule WHERE document_id=?",Long.class,doc.getId()));}
    private void cancelJob(Map<String,Object> run,Document doc){if(run.get("job_id")!=null && doc.getDeletedAt()==null)jobs.cancel(actor(run),(UUID)run.get("job_id"));}
    private void cancelRunJobs(KnowledgeBasePrincipal actor,UUID id){for(var job:jdbc.queryForList("SELECT job_id FROM kb_refresh_run WHERE document_id=? AND status IN ('PENDING','RUNNING','WAITING_INGESTION') AND job_id IS NOT NULL",UUID.class,id))jobs.cancel(actor,job);}
    private boolean activeRefresh(UUID doc){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM kb_refresh_run WHERE document_id=? AND status IN ('PENDING','RUNNING','WAITING_INGESTION'))",Boolean.class,doc));}
    private boolean activeJob(UUID doc){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM kb_ingestion_job WHERE document_id=? AND status IN ('PENDING','RUNNING'))",Boolean.class,doc));}
    private static void checkManual(Document doc,boolean confirm,Long expected){if(Boolean.TRUE.equals(doc.getHasManualChanges()) && (!confirm || expected==null))throw conflict("KB_MANUAL_OVERWRITE_REQUIRED","Refresh replaces manual content and chunk enabled settings; confirmOverwriteEdited=true and expectedDocumentVersion are required after review");if(expected!=null && !expected.equals(doc.getVersion()))throw conflict("KB_VERSION_CONFLICT","Reload the current document version");}
    private ScheduleView scheduleView(UUID doc){return jdbc.queryForObject("SELECT * FROM kb_refresh_schedule WHERE document_id=?",(rs,n)->new ScheduleView(doc,rs.getBoolean("enabled"),rs.getString("cron_expression"),rs.getString("timezone"),rs.getLong("version"),rs.getObject("next_run_at",OffsetDateTime.class),rs.getString("last_status"),rs.getString("last_reason"),rs.getObject("last_run_id",UUID.class),rs.getString("last_content_hash"),rs.getInt("consecutive_failures")),doc);}
    private RunView runView(UUID id){return jdbc.queryForObject("SELECT * FROM kb_refresh_run WHERE id=?",RefreshService::runView,id);}
    private static RunView runView(java.sql.ResultSet rs,int n)throws java.sql.SQLException{return new RunView(rs.getObject("id",UUID.class),rs.getObject("document_id",UUID.class),rs.getString("request_id"),rs.getString("trigger_type"),rs.getString("status"),rs.getObject("job_id",UUID.class),rs.getInt("attempts"),rs.getInt("max_attempts"),rs.getObject("http_status",Integer.class),rs.getString("reason"),rs.getString("error_message"),rs.getObject("next_run_at",OffsetDateTime.class),rs.getObject("started_at",OffsetDateTime.class),rs.getObject("completed_at",OffsetDateTime.class),rs.getObject("duration_ms",Long.class));}
    static KnowledgeBasePrincipal actor(Map<String,Object> row){return new KnowledgeBasePrincipal("persisted-refresh","unused",(String)row.get("tenant_id"),(String)row.get("owner_id"));}
    static Instant instant(Object value){return value instanceof OffsetDateTime offset?offset.toInstant():((java.sql.Timestamp)value).toInstant();}
    private static KnowledgeBaseException conflict(String code,String message){return new KnowledgeBaseException(409,code,message);}
}
