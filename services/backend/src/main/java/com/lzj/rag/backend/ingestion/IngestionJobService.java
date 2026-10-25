package com.lzj.rag.backend.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lzj.rag.backend.document.*;
import com.lzj.rag.backend.knowledgebase.*;
import com.lzj.rag.backend.model.EmbeddingClient;
import com.lzj.rag.backend.security.KnowledgeBasePrincipal;
import com.lzj.rag.framework.convention.page.PageResponse;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.*;
import java.util.*;

/** Database is the execution authority. Lock order is base -> document -> job. */
@Service
@EnableConfigurationProperties(IngestionWorkerProperties.class)
public class IngestionJobService {
    private final JdbcTemplate jdbc;
    private final KnowledgeBaseService bases;
    private final DocumentService documents;
    private final IngestionProperties properties;
    private final IngestionWorkerProperties worker;
    private final ObjectMapper json;
    private final String instance="ingest-"+UUID.randomUUID();
    private static final String SAFE_ERROR="Execution ended; inspect stage and errorCode. Source contents and provider details are omitted.";
    private static final String OWNED=" id=? AND status='RUNNING' AND claim_token=? AND lease_owner=? AND attempts=? AND lease_until>clock_timestamp() AND attempt_deadline>clock_timestamp()";
    public IngestionJobService(JdbcTemplate jdbc,KnowledgeBaseService bases,DocumentService documents,IngestionProperties properties,
            IngestionWorkerProperties worker,ObjectMapper json) {
        this.jdbc=jdbc;this.bases=bases;this.documents=documents;this.properties=properties;this.worker=worker;this.json=json;
        if(properties.deadline().isNegative() || properties.deadline().isZero() || properties.deadline().compareTo(Duration.ofMinutes(15))>0)
            throw new IllegalArgumentException("Ingestion attempt deadline must be positive and <=15 minutes");
    }
    public record Submission(JobView job,boolean replayed) { }
    private record Snapshot(String originalFilename,String mimeType,long fileSize,String namespace,EmbeddingClient.Binding binding) { }

    @Transactional(timeout=10)
    public Submission submit(KnowledgeBasePrincipal actor,UUID documentId,String requestId,UUID retryOf) {
        return submit(actor,documentId,requestId,retryOf,false,null);
    }
    @Transactional(timeout=10)
    public Submission submit(KnowledgeBasePrincipal actor,UUID documentId,String requestId,UUID retryOf,boolean confirmOverwriteEdited,Long expectedDocumentVersion) {
        if(requestId==null || !requestId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{7,127}"))
            throw new KnowledgeBaseException(400,"KB_INVALID_INPUT","requestId must contain 8..128 safe ASCII characters");
        // Serialize only this caller/key before business locks; hash collisions merely serialize unrelated keys.
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,actor.tenantId()+"/"+actor.userId()+"/"+requestId);
        documents.requireOwned(actor,documentId);
        var existing=jdbc.queryForList("SELECT * FROM kb_ingestion_job WHERE tenant_id=? AND owner_id=? AND request_id=?",actor.tenantId(),actor.userId(),requestId);
        if(!existing.isEmpty()) {
            var row=existing.get(0);
            if(!documentId.equals(row.get("document_id")) || !Objects.equals(retryOf,row.get("retry_of")) || confirmOverwriteEdited!=Boolean.TRUE.equals(row.get("confirm_overwrite_edited"))
                    || !Objects.equals(expectedDocumentVersion,row.get("expected_document_version")))
                throw new KnowledgeBaseException(409,"KB_IDEMPOTENCY_CONFLICT","requestId is already bound to another submission");
            return new Submission(getOwnedJob(actor,documentId,(UUID)row.get("id")),true);
        }
        if(retryOf!=null) {
            var previous=getOwnedJob(actor,documentId,retryOf);
            if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT refresh_run_id IS NOT NULL FROM kb_ingestion_job WHERE id=?",Boolean.class,retryOf)))
                throw new KnowledgeBaseException(409,"KB_REFRESH_RETRY_REQUIRED","Retry URL refresh through the document refresh endpoint to refetch and preserve baseline rules");
            if(!Set.of("FAILED","CANCELLED").contains(previous.status()))
                throw new KnowledgeBaseException(409,"KB_JOB_NOT_RETRYABLE","Only terminal failed or cancelled jobs can be explicitly retried");
        }
        UUID id=createLocked(actor,documentId,requestId,retryOf,worker.maxAttempts(),confirmOverwriteEdited,expectedDocumentVersion,null);
        return new Submission(getOwnedJob(actor,documentId,id),false);
    }
    private UUID createLocked(KnowledgeBasePrincipal actor,UUID documentId,String requestId,UUID retryOf,int maxAttempts,boolean confirmOverwriteEdited,Long expectedDocumentVersion,UUID refreshRun) {
        var doc=documents.requireOwned(actor,documentId);var base=bases.lockOwned(actor,doc.getKbId());
        jdbc.queryForObject("SELECT id FROM kb_document WHERE id=? FOR UPDATE",UUID.class,documentId);
        doc=documents.lockOwned(actor,documentId);
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM kb_refresh_run WHERE document_id=? AND status IN ('PENDING','RUNNING','WAITING_INGESTION') AND (?::uuid IS NULL OR id<>?))",Boolean.class,documentId,refreshRun,refreshRun)))
            throw new KnowledgeBaseException(409,"KB_REFRESH_ACTIVE","Wait for the active refresh before submitting ingestion");
        var active=jdbc.queryForList("SELECT id FROM kb_ingestion_job WHERE document_id=? AND status IN ('PENDING','RUNNING')",UUID.class,documentId);
        if(!active.isEmpty())throw new KnowledgeBaseException(409,"KB_JOB_ACTIVE","Document already has active ingestion job "+active.get(0));
        if(!doc.getEnabled())throw new KnowledgeBaseException(409,"KB_DOCUMENT_DISABLED","Enable the document before ingesting");
        if(base.getEmbeddingModelId()==null)throw new KnowledgeBaseException(409,"KB_MODEL_UNBOUND","Bind a model before ingesting");
        if(Boolean.TRUE.equals(doc.getHasManualChanges()) || Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM kb_chunk WHERE document_id=? AND generation=? AND edited)",Boolean.class,documentId,doc.getActiveGeneration()))) {
            if(!confirmOverwriteEdited || expectedDocumentVersion==null)
                throw new KnowledgeBaseException(409,"KB_MANUAL_OVERWRITE_REQUIRED","Re-ingestion replaces manual content, additions, deletions and chunk enabled settings; send confirmOverwriteEdited=true and expectedDocumentVersion after review");
        }
        if(expectedDocumentVersion!=null && !expectedDocumentVersion.equals(doc.getVersion()))
            throw new KnowledgeBaseException(409,"KB_VERSION_CONFLICT","Reload the document and review manual changes before confirming replacement");
        long generation=jdbc.queryForObject("SELECT next_generation+1 FROM kb_document WHERE id=?",Long.class,documentId);
        UUID id=UUID.randomUUID();
        try {
            var model=json.readTree(base.getEmbeddingConfigJson());
            var snapshot=new Snapshot(doc.getOriginalFilename(),doc.getMimeType(),doc.getFileSize(),base.getVectorNamespace(),
                    new EmbeddingClient.Binding(base.getEmbeddingModelId(),base.getEmbeddingProvider(),model.path("modelName").asText(),base.getEmbeddingDimension(),
                            model.path("embeddingSpace").asText(),model.path("configVersion").asText(),base.getEmbeddingConfigRevision(),base.getEmbeddingAiConfigRevision()));
            jdbc.update("""
                    INSERT INTO kb_ingestion_job(id,request_id,document_id,kb_id,tenant_id,owner_id,document_config_revision,document_version,
                    embedding_config_revision,target_generation,object_key,content_hash,ingestion_spec,status,stage,attempts,max_attempts,next_run_at,claim_token,input_snapshot,retry_of,confirm_overwrite_edited,expected_document_version)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,'PENDING','FETCH',0,?,clock_timestamp(),0,?::jsonb,?,?,?)
                    """,id,requestId,documentId,doc.getKbId(),actor.tenantId(),actor.userId(),doc.getConfigRevision(),doc.getVersion(),
                    base.getEmbeddingConfigRevision(),generation,doc.getObjectKey(),doc.getContentHash(),doc.getIngestionSpec(),maxAttempts,json.writeValueAsString(snapshot),retryOf,confirmOverwriteEdited,expectedDocumentVersion);
            jdbc.update("UPDATE kb_document SET next_generation=?,latest_job_id=?,process_status='PROCESSING',updated_at=clock_timestamp() WHERE id=?",generation,id,documentId);
            return id;
        } catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("Invalid persisted ingestion input",e);}
    }
    /** A refresh uses the same durable job, replacing only its immutable source snapshot. Caller holds base/document locks. */
    @Transactional(timeout=10)
    public UUID submitRefresh(KnowledgeBasePrincipal actor,Map<String,Object> run,long token) {
        UUID runId=(UUID)run.get("id"),document=(UUID)run.get("document_id");
        UUID id=createLocked(actor,document,"refresh-"+runId+"-"+token,null,worker.maxAttempts(),Boolean.TRUE.equals(run.get("confirm_overwrite_edited")),(Long)run.get("expected_document_version"),runId);
        jdbc.update("""
                UPDATE kb_ingestion_job SET object_key=?,content_hash=?,refresh_run_id=?,refresh_claim_token=?,
                input_snapshot=input_snapshot || jsonb_build_object('originalFilename',?::text,'mimeType',?::text,'fileSize',?::bigint) WHERE id=?
                """,run.get("candidate_object_key"),run.get("candidate_hash"),runId,token,run.get("candidate_filename"),run.get("candidate_mime"),run.get("candidate_size"),id);
        jdbc.update("UPDATE kb_refresh_run SET job_id=?,status='WAITING_INGESTION' WHERE id=?",id,runId);
        return id;
    }
    /** Single-attempt internal compatibility entry, sharing durable claims and the same execution path. */
    @Transactional(timeout=10)
    public IngestionContext start(KnowledgeBasePrincipal actor,UUID documentId) {
        var doc=documents.requireOwned(actor,documentId);bases.lockOwned(actor,doc.getKbId());
        jdbc.queryForObject("SELECT id FROM kb_document WHERE id=? FOR UPDATE",UUID.class,documentId);recoverLocked(documentId);
        UUID id=createLocked(actor,documentId,"internal-"+UUID.randomUUID(),null,1,false,null,null);
        return claimLocked(jdbc.queryForMap("SELECT * FROM kb_ingestion_job WHERE id=? FOR UPDATE",id));
    }
    /** Nonblocking locks follow publication's order, avoiding job -> base lock inversion. */
    @Transactional(timeout=10)
    public IngestionContext claimNext() {
        var candidates=jdbc.queryForList("""
                SELECT id,kb_id,document_id FROM kb_ingestion_job WHERE
                (status='PENDING' AND next_run_at<=clock_timestamp()) OR (status='RUNNING' AND lease_until<=clock_timestamp())
                ORDER BY COALESCE(next_run_at,lease_until),created_at,id LIMIT 64
                """);
        for(var candidate:candidates) {
            if(jdbc.queryForList("SELECT id FROM kb_knowledge_base WHERE id=? FOR UPDATE SKIP LOCKED",UUID.class,candidate.get("kb_id")).isEmpty())continue;
            if(jdbc.queryForList("SELECT id FROM kb_document WHERE id=? FOR UPDATE SKIP LOCKED",UUID.class,candidate.get("document_id")).isEmpty())continue;
            var rows=jdbc.queryForList("""
                    SELECT * FROM kb_ingestion_job WHERE id=? AND
                    ((status='PENDING' AND next_run_at<=clock_timestamp()) OR (status='RUNNING' AND lease_until<=clock_timestamp())) FOR UPDATE SKIP LOCKED
                    """,candidate.get("id"));
            if(rows.isEmpty())continue;
            var row=rows.get(0);
            if("RUNNING".equals(row.get("status"))) {
                finishAttempt(row,"KB_INGESTION_EXPIRED",true);continue;
            }
            var claimed=claimLocked(row);
            if(claimed!=null)return claimed;
        }
        return null;
    }
    private IngestionContext claimLocked(Map<String,Object> row) {
        var doc=jdbc.queryForMap("SELECT * FROM kb_document WHERE id=?",row.get("document_id"));
        var base=jdbc.queryForMap("SELECT * FROM kb_knowledge_base WHERE id=?",row.get("kb_id"));
        if(doc.get("deleted_at")!=null || base.get("deleted_at")!=null || !Boolean.TRUE.equals(doc.get("enabled"))
                || !row.get("id").equals(doc.get("latest_job_id")) || !row.get("document_version").equals(doc.get("version"))
                || !row.get("document_config_revision").equals(doc.get("config_revision")) || !row.get("embedding_config_revision").equals(base.get("embedding_config_revision"))) {
            terminal(row,"CANCELLED","KB_INGESTION_INPUT_CHANGED");return null;
        }
        var claimed=jdbc.queryForMap("""
                UPDATE kb_ingestion_job SET status='RUNNING',stage='FETCH',attempts=attempts+1,claim_token=claim_token+1,lease_owner=?,
                lease_until=clock_timestamp()+(?*interval '1 millisecond'),attempt_deadline=clock_timestamp()+(?*interval '1 millisecond'),
                started_at=COALESCE(started_at,clock_timestamp()),execution_started_at=NULL,next_run_at=NULL,completed_at=NULL,error_code=NULL,error_message=NULL
                WHERE id=? AND status='PENDING' AND claim_token=? AND attempts<max_attempts RETURNING *
                """,instance,Math.min(worker.lease().toMillis(),properties.deadline().toMillis()),properties.deadline().toMillis(),row.get("id"),row.get("claim_token"));
        return context(claimed);
    }
    private IngestionContext context(Map<String,Object> row) {
        try {
            var input=json.readValue(row.get("input_snapshot").toString(),Snapshot.class);
            var actor=new KnowledgeBasePrincipal("persisted-ingestion","unused",(String)row.get("tenant_id"),(String)row.get("owner_id"));
            Object deadline=row.get("attempt_deadline");
            Instant instant=deadline instanceof OffsetDateTime offset?offset.toInstant():((java.sql.Timestamp)deadline).toInstant();
            return new IngestionContext((UUID)row.get("id"),(String)row.get("request_id"),(UUID)row.get("document_id"),(UUID)row.get("kb_id"),actor,
                    ((Number)row.get("document_version")).longValue(),((Number)row.get("document_config_revision")).longValue(),((Number)row.get("target_generation")).longValue(),
                    (String)row.get("object_key"),input.originalFilename(),input.mimeType(),input.fileSize(),(String)row.get("content_hash"),
                    json.readValue(row.get("ingestion_spec").toString(),DocumentTypes.IngestionSpec.class),input.binding(),input.namespace(),
                    instant,((Number)row.get("attempts")).intValue(),((Number)row.get("claim_token")).longValue(),(String)row.get("lease_owner"));
        } catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("Invalid persisted job snapshot",e);}
    }
    public void enter(IngestionContext c) {
        if(jdbc.update("UPDATE kb_ingestion_job SET execution_started_at=clock_timestamp() WHERE"+OWNED+" AND execution_started_at IS NULL",c.jobId(),c.claimToken(),c.leaseOwner(),c.attempt())!=1)throw lost();
    }
    public boolean renew(IngestionContext c) {
        return jdbc.update("UPDATE kb_ingestion_job SET lease_until=LEAST(attempt_deadline,clock_timestamp()+(?*interval '1 millisecond')) WHERE"+OWNED,
                worker.lease().toMillis(),c.jobId(),c.claimToken(),c.leaseOwner(),c.attempt())==1;
    }
    /** An executor rejection returns the unstarted claim to the DB queue without consuming an attempt. */
    public void releaseUnstarted(IngestionContext c) {
        jdbc.update("UPDATE kb_ingestion_job SET status='PENDING',attempts=attempts-1,claim_token=claim_token+1,lease_owner=NULL,lease_until=NULL,attempt_deadline=NULL,next_run_at=clock_timestamp() WHERE"+OWNED+" AND execution_started_at IS NULL",
                c.jobId(),c.claimToken(),c.leaseOwner(),c.attempt());
    }
    @Transactional(timeout=10)
    public void begin(IngestionContext c,String stage,long inputs,String unit) {
        if(jdbc.update("UPDATE kb_ingestion_job SET stage=? WHERE"+OWNED,stage,c.jobId(),c.claimToken(),c.leaseOwner(),c.attempt())!=1)throw lost();
        jdbc.update("INSERT INTO kb_ingestion_stage_log(job_id,attempt,stage,status,input_count,input_unit,started_at) VALUES (?,?,?,'RUNNING',?,?,clock_timestamp())",c.jobId(),c.attempt(),stage,inputs,unit);
        com.lzj.rag.backend.operations.OperationLog.committed(c.kbId(),c.documentId(),c.jobId(),c.generation(),stage,"RUNNING");
    }
    @Transactional(timeout=10)
    public void end(IngestionContext c,String stage,long outputs,String unit) {
        if(jdbc.update("UPDATE kb_ingestion_job SET stage=stage WHERE"+OWNED,c.jobId(),c.claimToken(),c.leaseOwner(),c.attempt())!=1)throw lost();
        jdbc.update("""
                UPDATE kb_ingestion_stage_log SET status='SUCCEEDED',ended_at=clock_timestamp(),
                duration_ms=GREATEST(0,(EXTRACT(EPOCH FROM (clock_timestamp()-started_at))*1000)::bigint),output_count=?,output_unit=?
                WHERE job_id=? AND attempt=? AND stage=? AND status='RUNNING'
                """,outputs,unit,c.jobId(),c.attempt(),stage);
        com.lzj.rag.backend.operations.OperationLog.committed(c.kbId(),c.documentId(),c.jobId(),c.generation(),stage,"SUCCEEDED");
    }
    public void pinAiRevision(IngestionContext c,String revision) {
        if(jdbc.update("UPDATE kb_ingestion_job SET ai_config_revision=? WHERE"+OWNED,revision,c.jobId(),c.claimToken(),c.leaseOwner(),c.attempt())!=1)throw lost();
    }
    @Transactional(timeout=10)
    public void fail(IngestionContext c,RuntimeException error) {
        // Process shutdown/interruption is not user cancellation. Leave the durable lease for recovery.
        if(Thread.currentThread().isInterrupted())return;
        // An expired/cancelled/superseded worker cannot change even a failure state.
        jdbc.queryForObject("SELECT id FROM kb_knowledge_base WHERE id=? FOR UPDATE",UUID.class,c.kbId());
        jdbc.queryForObject("SELECT id FROM kb_document WHERE id=? FOR UPDATE",UUID.class,c.documentId());
        var rows=jdbc.queryForList("SELECT * FROM kb_ingestion_job WHERE"+OWNED+" FOR UPDATE",c.jobId(),c.claimToken(),c.leaseOwner(),c.attempt());
        if(rows.isEmpty())return;
        String code=error instanceof KnowledgeBaseException kb?kb.code():"KB_INGESTION_FAILED";
        if(Set.of("KB_JOB_CANCELLED","KB_INGESTION_INPUT_CHANGED").contains(code)) {
            terminal(rows.get(0),"CANCELLED",code);return;
        }
        boolean retryable=Set.of("KB_MODEL_RATE_LIMITED","KB_MODEL_UNAVAILABLE","KB_MODEL_TIMEOUT","KB_INGESTION_TIMEOUT","KB_STORAGE_UNAVAILABLE","KB_PARSE_SERVICE_UNAVAILABLE","KB_EXTERNAL_PARSE_TIMEOUT").contains(code)
                || error instanceof TransientDataAccessException || error instanceof org.springframework.dao.DataAccessResourceFailureException;
        finishAttempt(rows.get(0),code,retryable);
    }
    private void finishAttempt(Map<String,Object> row,String code,boolean retryable) {
        int attempt=((Number)row.get("attempts")).intValue();closeStages(row,"FAILED",code);
        if(retryable && attempt<((Number)row.get("max_attempts")).intValue()) {
            jdbc.update("""
                    UPDATE kb_ingestion_job SET status='PENDING',next_run_at=clock_timestamp()+(?*interval '1 millisecond'),claim_token=claim_token+1,
                    lease_owner=NULL,lease_until=NULL,attempt_deadline=NULL,error_code=?,error_message=? WHERE id=? AND claim_token=? AND status='RUNNING'
                    """,worker.backoffMillis(attempt),code,SAFE_ERROR,row.get("id"),row.get("claim_token"));
        } else terminal(row,"FAILED",code);
    }
    private void terminal(Map<String,Object> row,String status,String code) {
        jdbc.update("""
                UPDATE kb_ingestion_job SET status=?,completed_at=clock_timestamp(),next_run_at=NULL,claim_token=claim_token+1,
                lease_owner=NULL,lease_until=NULL,attempt_deadline=NULL,error_code=?,error_message=? WHERE id=? AND claim_token=? AND status=?
                """,status,code,SAFE_ERROR,row.get("id"),row.get("claim_token"),row.get("status"));
        closeStages(row,status,code);
        jdbc.update("""
                UPDATE kb_document SET process_status=CASE WHEN version<>? OR ?='CANCELLED' THEN 'PENDING' ELSE 'FAILED' END,updated_at=clock_timestamp()
                WHERE id=? AND latest_job_id=?
                """,row.get("document_version"),status,row.get("document_id"),row.get("id"));
        queueCleanup((UUID)row.get("document_id"),((Number)row.get("target_generation")).longValue());
    }
    private void closeStages(Map<String,Object> row,String status,String code) {
        jdbc.update("""
                UPDATE kb_ingestion_stage_log SET status=?,ended_at=clock_timestamp(),error_code=?,error_message=?,
                duration_ms=GREATEST(0,(EXTRACT(EPOCH FROM (clock_timestamp()-started_at))*1000)::bigint)
                WHERE job_id=? AND attempt=? AND status='RUNNING'
                """,status,code,SAFE_ERROR,row.get("id"),row.get("attempts"));
    }
    @Transactional(timeout=10)
    public JobView cancel(KnowledgeBasePrincipal actor,UUID jobId) {
        var job=getOwnedJob(actor,jobId);var doc=documents.requireOwned(actor,job.documentId());bases.lockOwned(actor,doc.getKbId());
        jdbc.queryForObject("SELECT id FROM kb_document WHERE id=? FOR UPDATE",UUID.class,job.documentId());
        var row=jdbc.queryForMap("SELECT * FROM kb_ingestion_job WHERE id=? FOR UPDATE",jobId);
        if(Set.of("PENDING","RUNNING").contains(row.get("status")))terminal(row,"CANCELLED","KB_JOB_CANCELLED");
        return getOwnedJob(actor,job.documentId(),jobId);
    }
    @Transactional(timeout=10)
    public void recoverExpired(KnowledgeBasePrincipal actor,UUID documentId) {
        var doc=documents.requireOwned(actor,documentId);bases.lockOwned(actor,doc.getKbId());
        jdbc.queryForObject("SELECT id FROM kb_document WHERE id=? FOR UPDATE",UUID.class,documentId);recoverLocked(documentId);
    }
    private void recoverLocked(UUID documentId) {
        for(var row:jdbc.queryForList("SELECT * FROM kb_ingestion_job WHERE document_id=? AND status='RUNNING' AND lease_until<=clock_timestamp() FOR UPDATE",documentId))
            finishAttempt(row,"KB_INGESTION_EXPIRED",true);
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void queueCleanup(UUID documentId,long generation) {
        jdbc.update("INSERT INTO kb_generation_cleanup(id,document_id,generation,next_run_at) VALUES (?,?,?,clock_timestamp()+(?*interval '1 millisecond')) ON CONFLICT(document_id,generation) DO NOTHING",
                UUID.randomUUID(),documentId,generation,worker.cleanupDelay().toMillis());
    }
    private static KnowledgeBaseException lost(){return new KnowledgeBaseException(409,"KB_JOB_EXPIRED","This execution no longer owns an active job lease");}
    public record JobView(UUID id,String requestId,UUID documentId,long documentConfigRevision,long targetGeneration,
            String jobType,String status,String stage,int attempts,int maxAttempts,OffsetDateTime createdAt,OffsetDateTime startedAt,OffsetDateTime completedAt,
            OffsetDateTime nextRunAt,OffsetDateTime leaseUntil,String errorCode,String errorMessage,String aiConfigRevision,UUID retryOf) { }
    public record StageView(String stage,int attempt,String status,OffsetDateTime startedAt,OffsetDateTime endedAt,Long durationMs,
            Long inputCount,String inputUnit,Long outputCount,String outputUnit,String errorCode,String errorMessage) { }
    public JobView getOwnedJob(KnowledgeBasePrincipal actor,UUID jobId) {
        var ids=jdbc.queryForList("SELECT document_id FROM kb_ingestion_job WHERE id=? AND tenant_id=? AND owner_id=?",UUID.class,jobId,actor.tenantId(),actor.userId());
        if(ids.isEmpty())throw new KnowledgeBaseException(404,"KB_JOB_NOT_FOUND","Ingestion job not found");
        return getOwnedJob(actor,ids.get(0),jobId);
    }
    public JobView getOwnedJob(KnowledgeBasePrincipal actor,UUID documentId,UUID jobId) {
        documents.requireOwned(actor,documentId);
        var rows=jdbc.query("SELECT * FROM kb_ingestion_job WHERE id=? AND document_id=? AND tenant_id=? AND owner_id=?",IngestionJobService::jobView,jobId,documentId,actor.tenantId(),actor.userId());
        if(rows.isEmpty())throw new KnowledgeBaseException(404,"KB_JOB_NOT_FOUND","Ingestion job not found");
        return rows.get(0);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,timeout=10)
    public PageResponse<JobView> list(KnowledgeBasePrincipal actor,UUID documentId,int current,int size,String status) {
        documents.requireOwned(actor,documentId);
        if(!status.isEmpty() && !Set.of("PENDING","RUNNING","SUCCEEDED","FAILED","CANCELLED").contains(status))throw new KnowledgeBaseException(400,"KB_INVALID_INPUT","Unknown job status");
        String filter=" WHERE document_id=? AND tenant_id=? AND owner_id=? AND (?='' OR status=?)";
        Long total=jdbc.queryForObject("SELECT count(*) FROM kb_ingestion_job"+filter,Long.class,documentId,actor.tenantId(),actor.userId(),status,status);
        var records=jdbc.query("SELECT * FROM kb_ingestion_job"+filter+" ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",IngestionJobService::jobView,
                documentId,actor.tenantId(),actor.userId(),status,status,size,((long)current-1)*size);
        return new PageResponse<>(current,size,total,records);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,timeout=10)
    public PageResponse<StageView> stages(KnowledgeBasePrincipal actor,UUID documentId,UUID jobId,int current,int size) {
        getOwnedJob(actor,documentId,jobId);
        Long total=jdbc.queryForObject("SELECT count(*) FROM kb_ingestion_stage_log WHERE job_id=?",Long.class,jobId);
        var records=jdbc.query("""
                SELECT * FROM kb_ingestion_stage_log WHERE job_id=? ORDER BY attempt,
                array_position(ARRAY['FETCH','PARSE','SPLIT','EMBED','PERSIST','PUBLISH'],stage) LIMIT ? OFFSET ?
                """,(rs,n)->new StageView(rs.getString("stage"),rs.getInt("attempt"),rs.getString("status"),
                rs.getObject("started_at",OffsetDateTime.class),rs.getObject("ended_at",OffsetDateTime.class),rs.getObject("duration_ms",Long.class),
                rs.getObject("input_count",Long.class),rs.getString("input_unit"),rs.getObject("output_count",Long.class),rs.getString("output_unit"),rs.getString("error_code"),rs.getString("error_message")),
                jobId,size,((long)current-1)*size);
        return new PageResponse<>(current,size,total,records);
    }
    private static JobView jobView(java.sql.ResultSet rs,int row)throws java.sql.SQLException {
        return new JobView(rs.getObject("id",UUID.class),rs.getString("request_id"),rs.getObject("document_id",UUID.class),
                rs.getLong("document_config_revision"),rs.getLong("target_generation"),rs.getString("job_type"),rs.getString("status"),rs.getString("stage"),rs.getInt("attempts"),rs.getInt("max_attempts"),
                rs.getObject("created_at",OffsetDateTime.class),rs.getObject("started_at",OffsetDateTime.class),rs.getObject("completed_at",OffsetDateTime.class),
                rs.getObject("next_run_at",OffsetDateTime.class),rs.getObject("lease_until",OffsetDateTime.class),rs.getString("error_code"),rs.getString("error_message"),rs.getString("ai_config_revision"),rs.getObject("retry_of",UUID.class));
    }
}
