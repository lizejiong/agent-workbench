package com.lzj.rag.backend;
import org.springframework.jdbc.core.JdbcTemplate;

final class TestDatabaseCleanup {
    private TestDatabaseCleanup(){ }
    static void clear(JdbcTemplate jdbc){
        jdbc.update("UPDATE kb_document SET latest_job_id=NULL");
        jdbc.update("UPDATE kb_ingestion_job SET retry_of=NULL");
        jdbc.update("UPDATE kb_ingestion_job SET refresh_run_id=NULL");
        for(String table:new String[]{"kb_url_import","kb_remote_object_cleanup","kb_refresh_run","kb_refresh_schedule","kb_content_audit","kb_document_cleanup","kb_generation_cleanup","kb_vector","kb_chunk","kb_generation","kb_ingestion_stage_log","kb_ingestion_job","kb_keyword_cleanup","kb_keyword_sync","kb_document","kb_upload_operation","kb_knowledge_base"})
            jdbc.update("DELETE FROM "+table);
    }
}
