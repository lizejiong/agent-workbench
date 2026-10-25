package com.lzj.rag.backend.search;

import com.lzj.rag.backend.document.DocumentService;
import com.lzj.rag.backend.knowledgebase.*;
import com.lzj.rag.backend.security.KnowledgeBasePrincipal;
import com.lzj.rag.framework.convention.page.PageResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Coalescing transactional outbox: immutable ES revision keys plus a fenced PG visibility switch. */
@Service
public class KeywordSyncService {
    public record Claim(UUID documentId,UUID kbId,long token,long revision,boolean cleanup,Instant deadline) { }
    public record View(UUID documentId,long revision,long syncedRevision,String status,int attempts,OffsetDateTime nextRunAt,
            OffsetDateTime leaseUntil,String errorCode,OffsetDateTime syncedAt,String cleanupStatus,String cleanupErrorCode) { }
    private final JdbcTemplate jdbc;private final KeywordIndex index;private final KeywordProperties config;
    private final KnowledgeBaseService bases;private final DocumentService documents;private final KnowledgeSearchService sources;
    private final TransactionTemplate tx;private final Map<String,Claim> running=new ConcurrentHashMap<>();
    private final String instance="keyword-"+UUID.randomUUID();
    public KeywordSyncService(JdbcTemplate jdbc,KeywordIndex index,KeywordProperties config,KnowledgeBaseService bases,DocumentService documents,
            KnowledgeSearchService sources,PlatformTransactionManager manager) {this.jdbc=jdbc;this.index=index;this.config=config;this.bases=bases;this.documents=documents;this.sources=sources;tx=new TransactionTemplate(manager);tx.setTimeout(10);}
    @Transactional(readOnly=true) public View get(KnowledgeBasePrincipal actor,UUID doc){documents.requireOwned(actor,doc);return view(doc);}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public PageResponse<View> list(KnowledgeBasePrincipal actor,UUID kb,int page,int size) {
        bases.requireOwned(actor,kb);long count=jdbc.queryForObject("SELECT count(*) FROM kb_keyword_sync WHERE kb_id=? AND tenant_id=? AND owner_id=?",Long.class,kb,actor.tenantId(),actor.userId());
        var ids=jdbc.queryForList("SELECT document_id FROM kb_keyword_sync WHERE kb_id=? AND tenant_id=? AND owner_id=? ORDER BY updated_at DESC,document_id LIMIT ? OFFSET ?",UUID.class,kb,actor.tenantId(),actor.userId(),size,((long)page-1)*size);
        return new PageResponse<>(page,size,count,ids.stream().map(this::view).toList());
    }
    @Transactional(timeout=10) public View retry(KnowledgeBasePrincipal actor,UUID kb,UUID doc) {
        bases.lockOwned(actor,kb);var docs=jdbc.queryForList("SELECT id FROM kb_document WHERE id=? AND kb_id=? AND tenant_id=? AND owner_id=? FOR UPDATE",UUID.class,doc,kb,actor.tenantId(),actor.userId());
        if(docs.isEmpty())throw new KnowledgeBaseException(404,"KB_DOCUMENT_NOT_FOUND","Document not found");
        var row=jdbc.queryForMap("SELECT * FROM kb_keyword_sync WHERE document_id=? FOR UPDATE",doc);
        if("RUNNING".equals(row.get("status")))throw new KnowledgeBaseException(409,"KB_KEYWORD_SYNC_ACTIVE","Wait for sync lease recovery before rebuilding");
        jdbc.update("UPDATE kb_keyword_sync SET revision=revision+1,status='PENDING',attempts=0,next_run_at=clock_timestamp(),claim_token=claim_token+1,error_code=NULL WHERE document_id=?",doc);
        jdbc.update("UPDATE kb_keyword_cleanup SET status='PENDING',attempts=0,next_run_at=clock_timestamp(),error_code=NULL WHERE document_id=? AND status='FAILED'",doc);
        return view(doc);
    }
    private View view(UUID doc) {return jdbc.queryForObject("SELECT s.*,c.status AS cleanup_status,c.error_code AS cleanup_error FROM kb_keyword_sync s LEFT JOIN kb_keyword_cleanup c USING(document_id) WHERE s.document_id=?",(rs,n)->new View(doc,rs.getLong("revision"),rs.getLong("synced_revision"),rs.getString("status"),rs.getInt("attempts"),rs.getObject("next_run_at",OffsetDateTime.class),rs.getObject("lease_until",OffsetDateTime.class),rs.getString("error_code"),rs.getObject("synced_at",OffsetDateTime.class),rs.getString("cleanup_status"),rs.getString("cleanup_error")),doc);}
    public Claim claimNext(boolean cleanup) {
        if(!config.enabled())return null;
        return tx.execute(status->{
            String table=cleanup?"kb_keyword_cleanup":"kb_keyword_sync";
            if(!cleanup)jdbc.update("UPDATE kb_keyword_sync SET revision=revision+1,status='PENDING',attempts=0,next_run_at=clock_timestamp() WHERE status='SUCCEEDED' AND indexed_target<>?",config.target());
            String due=cleanup?"(status IN ('PENDING','SUCCEEDED') AND next_run_at<=clock_timestamp())":"(status='PENDING' AND next_run_at<=clock_timestamp())";
            for(var row:jdbc.queryForList("SELECT document_id,kb_id FROM "+table+" WHERE "+due+" OR (status='RUNNING' AND lease_until<=clock_timestamp()) ORDER BY next_run_at,document_id LIMIT 32")) {
                if(!locks((UUID)row.get("kb_id"),(UUID)row.get("document_id"),true))continue;
                var candidates=jdbc.queryForList("SELECT * FROM "+table+" WHERE document_id=? AND ("+due+" OR (status='RUNNING' AND lease_until<=clock_timestamp())) FOR UPDATE SKIP LOCKED",row.get("document_id"));if(candidates.isEmpty())continue;
                var item=candidates.get(0);int attempts=((Number)item.get("attempts")).intValue();
                if(!cleanup && "RUNNING".equals(item.get("status")) && !Objects.equals(item.get("revision"),item.get("claimed_revision"))) {
                    jdbc.update("UPDATE kb_keyword_sync SET status='PENDING',attempts=0,claim_token=claim_token+1,lease_owner=NULL,lease_until=NULL,next_run_at=clock_timestamp() WHERE document_id=?",row.get("document_id"));continue;
                }
                if(attempts>=config.maxAttempts() && !"SUCCEEDED".equals(item.get("status"))){jdbc.update("UPDATE "+table+" SET status='FAILED',error_code='KB_KEYWORD_ATTEMPTS_EXHAUSTED',lease_owner=NULL,lease_until=NULL WHERE document_id=?",row.get("document_id"));continue;}
                var claimed=jdbc.queryForMap("UPDATE "+table+" SET status='RUNNING',attempts=CASE WHEN status='SUCCEEDED' THEN 1 ELSE attempts+1 END,claim_token=claim_token+1,lease_owner=?,lease_until=clock_timestamp()+(?*interval '1 millisecond'),attempt_deadline=clock_timestamp()+interval '2 minutes',"+(cleanup?"claimed_watermark=watermark":"claimed_revision=revision")+" WHERE document_id=? RETURNING *",instance,config.lease().toMillis(),row.get("document_id"));
                Object deadline=claimed.get("attempt_deadline");Instant instant=deadline instanceof OffsetDateTime offset?offset.toInstant():((java.sql.Timestamp)deadline).toInstant();
                return new Claim((UUID)row.get("document_id"),(UUID)row.get("kb_id"),((Number)claimed.get("claim_token")).longValue(),((Number)claimed.get(cleanup?"claimed_watermark":"claimed_revision")).longValue(),cleanup,instant);
            }
            return null;
        });
    }
    public boolean runOnce(){var claim=claimNext(false);if(claim!=null)execute(claim);var cleanup=claimNext(true);if(cleanup!=null)execute(cleanup);return claim!=null || cleanup!=null;}
    public void execute(Claim claim) {
        String key=claim.cleanup()+"/"+claim.documentId();if(running.putIfAbsent(key,claim)!=null)return;
        try {
            if(claim.cleanup())index.deleteBefore(claim.documentId(),claim.revision(),claim.deadline());
            else {
                var rows=tx.execute(status->{locks(claim.kbId(),claim.documentId(),false);owned(claim);
                    return jdbc.queryForList("""
                        SELECT c.*,d.name AS document_name,d.enabled AS document_enabled FROM kb_chunk c JOIN kb_document d ON d.id=c.document_id
                        JOIN kb_knowledge_base b ON b.id=d.kb_id
                        WHERE d.id=? AND d.active_generation=c.generation
                        AND d.deleted_at IS NULL AND b.deleted_at IS NULL ORDER BY c.chunk_index,c.id
                        """,claim.documentId());});
                var chunks=new ArrayList<KeywordIndex.IndexedChunk>();
                for(var row:rows)chunks.add(new KeywordIndex.IndexedChunk(claim.documentId()+":"+claim.revision()+":"+row.get("id"),(UUID)row.get("id"),(UUID)row.get("kb_id"),claim.documentId(),(String)row.get("tenant_id"),(String)row.get("owner_id"),((Number)row.get("generation")).longValue(),claim.revision(),claim.documentId()+":"+claim.revision(),(String)row.get("content_hash"),(String)row.get("document_name"),((Number)row.get("chunk_index")).intValue(),(String)row.get("content"),Boolean.TRUE.equals(row.get("enabled")) && Boolean.TRUE.equals(row.get("document_enabled")),Boolean.TRUE.equals(row.get("enabled")),Boolean.TRUE.equals(row.get("document_enabled")),sources.source(row.get("metadata_json").toString())));
                index.write(chunks,claim.deadline());
            }
            tx.executeWithoutResult(status->{locks(claim.kbId(),claim.documentId(),false);var row=owned(claim);
                if(claim.cleanup()) {
                    boolean changed=((Number)row.get("watermark")).longValue()!=claim.revision();
                    jdbc.update("UPDATE kb_keyword_cleanup SET status=?,attempts=0,next_run_at=clock_timestamp()+(?*interval '1 second'),lease_owner=NULL,lease_until=NULL,error_code=NULL WHERE document_id=?",changed?"PENDING":"SUCCEEDED",changed?0:3600,claim.documentId());
                } else if(((Number)row.get("revision")).longValue()!=claim.revision())releaseChanged(claim);
                else {
                    jdbc.update("UPDATE kb_keyword_sync SET status='SUCCEEDED',synced_revision=?,indexed_target=?,synced_at=clock_timestamp(),next_run_at=NULL,lease_owner=NULL,lease_until=NULL,error_code=NULL WHERE document_id=?",claim.revision(),config.target(),claim.documentId());
                    jdbc.update("""
                        INSERT INTO kb_keyword_cleanup(document_id,kb_id,watermark,next_run_at) VALUES (?,?,?,clock_timestamp()+interval '40 seconds')
                        ON CONFLICT(document_id) DO UPDATE SET watermark=GREATEST(kb_keyword_cleanup.watermark,EXCLUDED.watermark),
                        status=CASE WHEN kb_keyword_cleanup.status='RUNNING' THEN 'RUNNING' ELSE 'PENDING' END,
                        attempts=CASE WHEN kb_keyword_cleanup.status='RUNNING' THEN kb_keyword_cleanup.attempts ELSE 0 END,
                        next_run_at=clock_timestamp()+interval '40 seconds'
                        """,claim.documentId(),claim.kbId(),claim.revision());
                }
            });
        }catch(RuntimeException error) {
            if(Thread.currentThread().isInterrupted())return;
            tx.executeWithoutResult(status->{locks(claim.kbId(),claim.documentId(),false);Map<String,Object> row;try{row=owned(claim);}catch(KnowledgeBaseException lost){return;}
                if(!claim.cleanup() && ((Number)row.get("revision")).longValue()!=claim.revision()){releaseChanged(claim);return;}
                boolean terminal=((Number)row.get("attempts")).intValue()>=config.maxAttempts();
                jdbc.update("UPDATE "+(claim.cleanup()?"kb_keyword_cleanup":"kb_keyword_sync")+" SET status=?,next_run_at=clock_timestamp()+(?*interval '1 second'),lease_owner=NULL,lease_until=NULL,error_code=? WHERE document_id=?",terminal?"FAILED":"PENDING",Math.min(60,2L<<Math.min(8,((Number)row.get("attempts")).intValue()-1)),error instanceof KnowledgeBaseException kb?kb.code():"KB_KEYWORD_SYNC_FAILED",claim.documentId());
            });
        }finally{running.remove(key,claim);}
    }
    public void heartbeat() {
        for(var claim:running.values())tx.executeWithoutResult(status->{if(!locks(claim.kbId(),claim.documentId(),true))return;
            jdbc.update("UPDATE "+(claim.cleanup()?"kb_keyword_cleanup":"kb_keyword_sync")+" SET lease_until=LEAST(attempt_deadline,clock_timestamp()+(?*interval '1 millisecond')) WHERE document_id=? AND status='RUNNING' AND claim_token=? AND lease_owner=? AND lease_until>clock_timestamp() AND attempt_deadline>clock_timestamp()",config.lease().toMillis(),claim.documentId(),claim.token(),instance);});
    }
    private Map<String,Object> owned(Claim claim) {
        var rows=jdbc.queryForList("SELECT * FROM "+(claim.cleanup()?"kb_keyword_cleanup":"kb_keyword_sync")+" WHERE document_id=? AND status='RUNNING' AND claim_token=? AND lease_owner=? AND lease_until>clock_timestamp() AND attempt_deadline>clock_timestamp() FOR UPDATE",claim.documentId(),claim.token(),instance);
        if(rows.isEmpty())throw new KnowledgeBaseException(409,"KB_KEYWORD_CLAIM_LOST","Keyword sync lease expired");return rows.get(0);
    }
    private void releaseChanged(Claim claim){jdbc.update("UPDATE kb_keyword_sync SET status='PENDING',attempts=0,next_run_at=clock_timestamp(),claim_token=claim_token+1,lease_owner=NULL,lease_until=NULL WHERE document_id=?",claim.documentId());}
    private boolean locks(UUID kb,UUID document,boolean skip){String suffix=" FOR UPDATE"+(skip?" SKIP LOCKED":"");return !jdbc.queryForList("SELECT id FROM kb_knowledge_base WHERE id=?"+suffix,UUID.class,kb).isEmpty() && !jdbc.queryForList("SELECT id FROM kb_document WHERE id=?"+suffix,UUID.class,document).isEmpty();}
}
