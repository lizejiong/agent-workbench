package com.lzj.rag.backend.refresh;

import com.lzj.rag.backend.ingestion.IngestionWorkerProperties;
import com.lzj.rag.backend.knowledgebase.KnowledgeBaseException;
import com.lzj.rag.backend.storage.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

/** Stable keys are never reused. A grace period covers an already-started bounded PUT from a lost worker. */
@Service
public class RemoteObjectCleanup {
    private final JdbcTemplate jdbc;private final ObjectStorageClient storage;private final StorageProperties storageConfig;
    private final IngestionWorkerProperties worker;private final TransactionTemplate tx;
    private final String instance="remote-gc-"+UUID.randomUUID();
    public RemoteObjectCleanup(JdbcTemplate jdbc,ObjectStorageClient storage,StorageProperties storageConfig,IngestionWorkerProperties worker,PlatformTransactionManager transactions){this.jdbc=jdbc;this.storage=storage;this.storageConfig=storageConfig;this.worker=worker;tx=new TransactionTemplate(transactions);tx.setTimeout(10);}
    @Scheduled(fixedDelayString="${kb.remote.cleanup-poll-delay:10000}",initialDelayString="${kb.worker.initial-delay:1000}")
    public void scan(){if(!worker.enabled())return;try{runOnce();}catch(RuntimeException error){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Remote object cleanup deferred; durable intent remains pending");}}
    public boolean runOnce() {
        var claim=tx.execute(status->{
            jdbc.update("""
                    UPDATE kb_remote_object_cleanup c SET status='PENDING' WHERE status='WAITING' AND next_run_at<=clock_timestamp()
                    AND NOT EXISTS(SELECT 1 FROM kb_refresh_run r WHERE r.id=c.run_id AND r.status IN ('PENDING','RUNNING','WAITING_INGESTION') AND r.candidate_object_key=c.object_key)
                    """);
            for(var c:jdbc.queryForList("SELECT id,kb_id,document_id FROM kb_remote_object_cleanup WHERE (status='PENDING' AND next_run_at<=clock_timestamp()) OR (status='RUNNING' AND lease_until<=clock_timestamp()) ORDER BY next_run_at,id LIMIT 32")) {
                if(jdbc.queryForList("SELECT id FROM kb_knowledge_base WHERE id=? FOR UPDATE SKIP LOCKED",UUID.class,c.get("kb_id")).isEmpty() || jdbc.queryForList("SELECT id FROM kb_document WHERE id=? FOR UPDATE SKIP LOCKED",UUID.class,c.get("document_id")).isEmpty())continue;
                var rows=jdbc.queryForList("SELECT * FROM kb_remote_object_cleanup WHERE id=? AND ((status='PENDING' AND next_run_at<=clock_timestamp()) OR (status='RUNNING' AND lease_until<=clock_timestamp())) FOR UPDATE SKIP LOCKED",c.get("id"));if(rows.isEmpty())continue;var row=rows.get(0);
                if(((Number)row.get("attempts")).intValue()>=worker.cleanupMaxAttempts()){jdbc.update("UPDATE kb_remote_object_cleanup SET status='FAILED',error_code='KB_CLEANUP_ATTEMPTS_EXHAUSTED',lease_owner=NULL,lease_until=NULL WHERE id=?",c.get("id"));continue;}
                if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM kb_document WHERE object_key=? AND deleted_at IS NULL) OR EXISTS(SELECT 1 FROM kb_refresh_run WHERE candidate_object_key=? AND status IN ('PENDING','RUNNING','WAITING_INGESTION'))",Boolean.class,row.get("object_key"),row.get("object_key"))))continue;
                return jdbc.queryForMap("UPDATE kb_remote_object_cleanup SET status='RUNNING',attempts=attempts+1,claim_token=claim_token+1,lease_owner=?,lease_until=clock_timestamp()+(?*interval '1 millisecond') WHERE id=? RETURNING *",instance,storageConfig.apiTimeout().multipliedBy(2).plusSeconds(30).toMillis(),c.get("id"));
            }
            return null;
        });
        if(claim==null)return false;
        String code=null;try{storage.delete((String)claim.get("object_key"));}catch(RuntimeException error){code=error instanceof KnowledgeBaseException kb?kb.code():"KB_STORAGE_UNAVAILABLE";}
        final String errorCode=code;
        tx.executeWithoutResult(status->{
            jdbc.queryForObject("SELECT id FROM kb_knowledge_base WHERE id=? FOR UPDATE",UUID.class,claim.get("kb_id"));jdbc.queryForObject("SELECT id FROM kb_document WHERE id=? FOR UPDATE",UUID.class,claim.get("document_id"));
            String terminal=errorCode==null?"SUCCEEDED":((Number)claim.get("attempts")).intValue()>=worker.cleanupMaxAttempts()?"FAILED":"PENDING";
            jdbc.update("UPDATE kb_remote_object_cleanup SET status=?,error_code=?,completed_at=CASE WHEN ?='SUCCEEDED' THEN clock_timestamp() ELSE NULL END,next_run_at=clock_timestamp()+(?*interval '1 millisecond'),lease_owner=NULL,lease_until=NULL WHERE id=? AND status='RUNNING' AND claim_token=? AND lease_owner=? AND lease_until>clock_timestamp()",terminal,errorCode,terminal,worker.backoffMillis(((Number)claim.get("attempts")).intValue()),claim.get("id"),claim.get("claim_token"),instance);
        });return true;
    }
}
