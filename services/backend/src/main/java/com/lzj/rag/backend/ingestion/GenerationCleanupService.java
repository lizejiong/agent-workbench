package com.lzj.rag.backend.ingestion;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Same-database cleanup uses row locks for its whole short transaction; crashes roll back the attempt. */
@Service
public class GenerationCleanupService {
    private final JdbcTemplate jdbc;private final IngestionWorkerProperties config;
    public GenerationCleanupService(JdbcTemplate jdbc,IngestionWorkerProperties config){this.jdbc=jdbc;this.config=config;}
    public List<UUID> due(){return jdbc.queryForList("SELECT id FROM kb_generation_cleanup WHERE status='PENDING' AND next_run_at<=clock_timestamp() ORDER BY next_run_at,id LIMIT 16",UUID.class);}
    @Transactional(timeout=15)
    public boolean clean(UUID id) {
        var candidates=jdbc.queryForList("SELECT c.document_id,d.kb_id FROM kb_generation_cleanup c JOIN kb_document d ON d.id=c.document_id WHERE c.id=?",id);
        if(candidates.isEmpty())return false;
        var c=candidates.get(0);
        if(jdbc.queryForList("SELECT id FROM kb_knowledge_base WHERE id=? FOR UPDATE SKIP LOCKED",UUID.class,c.get("kb_id")).isEmpty())return false;
        var docs=jdbc.queryForList("SELECT * FROM kb_document WHERE id=? FOR UPDATE SKIP LOCKED",c.get("document_id"));
        if(docs.isEmpty())return false;
        var rows=jdbc.queryForList("SELECT * FROM kb_generation_cleanup WHERE id=? AND status='PENDING' AND next_run_at<=clock_timestamp() FOR UPDATE SKIP LOCKED",id);
        if(rows.isEmpty())return false;
        var row=rows.get(0);Object generation=row.get("generation");
        if(Objects.equals(generation,docs.get(0).get("active_generation")) || Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM kb_ingestion_job WHERE document_id=? AND target_generation=? AND status IN ('PENDING','RUNNING'))",Boolean.class,c.get("document_id"),generation))) {
            jdbc.update("UPDATE kb_generation_cleanup SET next_run_at=clock_timestamp()+interval '1 minute' WHERE id=?",id);return false;
        }
        jdbc.update("DELETE FROM kb_chunk WHERE document_id=? AND generation=?",c.get("document_id"),generation); // vector FK cascades
        jdbc.update("DELETE FROM kb_generation WHERE document_id=? AND generation=?",c.get("document_id"),generation);
        jdbc.update("UPDATE kb_generation_cleanup SET status='SUCCEEDED',attempts=attempts+1,completed_at=clock_timestamp(),error_code=NULL WHERE id=?",id);
        return true;
    }
    @Transactional(timeout=10)
    public void failed(UUID id) {
        jdbc.update("""
                UPDATE kb_generation_cleanup SET attempts=attempts+1,status=CASE WHEN attempts+1>=? THEN 'FAILED' ELSE 'PENDING' END,
                next_run_at=clock_timestamp()+(?*interval '1 millisecond'),error_code='KB_GENERATION_CLEANUP_FAILED'
                WHERE id=? AND status='PENDING'
                """,config.cleanupMaxAttempts(),config.maxBackoff().toMillis(),id);
    }
}
