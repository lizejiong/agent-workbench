package com.lzj.rag.backend.chunk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lzj.rag.backend.ingestion.*;
import com.lzj.rag.backend.knowledgebase.*;
import com.lzj.rag.backend.model.EmbeddingClient;
import com.lzj.rag.backend.vector.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class ChunkIndexWriter {
    private final JdbcTemplate jdbc;private final VectorStore vectors;private final KnowledgeBaseService bases;
    private final IngestionJobService jobs;private final ObjectMapper json;private final IngestionProperties config;
    private final com.lzj.rag.backend.refresh.RefreshState refresh;
    public ChunkIndexWriter(JdbcTemplate jdbc,VectorStore vectors,KnowledgeBaseService bases,IngestionJobService jobs,ObjectMapper json,IngestionProperties config,com.lzj.rag.backend.refresh.RefreshState refresh){
        this.jdbc=jdbc;this.vectors=vectors;this.bases=bases;this.jobs=jobs;this.json=json;this.config=config;
        this.refresh=refresh;
    }
    @Transactional(timeout=30)
    public void writeAndPublish(IngestionContext context,List<Chunk> chunks,EmbeddingClient.Result embeddings) {
        var base=bases.lockOwned(context.actor(),context.kbId());
        var document=jdbc.queryForMap("SELECT * FROM kb_document WHERE id=? FOR UPDATE",context.documentId());
        var job=jdbc.queryForMap("SELECT *,lease_until>clock_timestamp() AS valid FROM kb_ingestion_job WHERE id=? FOR UPDATE",context.jobId());
        if(!"RUNNING".equals(job.get("status")) || !Boolean.TRUE.equals(job.get("valid")) || ((Number)job.get("claim_token")).longValue()!=context.claimToken()
                || !context.leaseOwner().equals(job.get("lease_owner")) || ((Number)job.get("attempts")).intValue()!=context.attempt()
                || !context.jobId().equals(document.get("latest_job_id")) || ((Number)document.get("version")).longValue()!=context.documentVersion()
                || ((Number)document.get("config_revision")).longValue()!=context.configRevision() || document.get("deleted_at")!=null || !Boolean.TRUE.equals(document.get("enabled"))
                || !base.getEmbeddingConfigRevision().equals(context.binding().localRevision()) || !Objects.equals(job.get("ai_config_revision"),embeddings.aiConfigRevision()))
            throw new KnowledgeBaseException(409,"KB_INGESTION_INPUT_CHANGED","Ingestion input or worker lease changed; run again with the current configuration");
        if(chunks.isEmpty() || chunks.size()>config.maxChunks() || chunks.size()!=embeddings.vectors().size() || !embeddings.aiConfigRevision().matches("[0-9a-f]{64}"))
            throw new KnowledgeBaseException(502,"KB_MODEL_INVALID_RESPONSE","Chunk/vector counts or identity do not match");
        refresh.beforePublish(context);
        if(base.getEmbeddingAiConfigRevision()!=null && !base.getEmbeddingAiConfigRevision().equals(embeddings.aiConfigRevision()))
            throw new KnowledgeBaseException(409,"KB_MODEL_CONFIG_CHANGED","AI revision is already pinned; migrate using a new knowledge base");
        if(base.getEmbeddingAiConfigRevision()==null)
            jdbc.update("UPDATE kb_knowledge_base SET embedding_ai_config_revision=?,updated_at=clock_timestamp() WHERE id=?",embeddings.aiConfigRevision(),context.kbId());
        jdbc.update("""
                INSERT INTO kb_generation(document_id,generation,kb_id,tenant_id,owner_id,job_id,embedding_config_revision,ai_config_revision,chunk_count,next_chunk_index)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """,context.documentId(),context.generation(),context.kbId(),context.actor().tenantId(),context.actor().userId(),context.jobId(),context.binding().localRevision(),embeddings.aiConfigRevision(),chunks.size(),chunks.size());
        jdbc.batchUpdate("""
                INSERT INTO kb_chunk(id,kb_id,document_id,generation,tenant_id,owner_id,chunk_index,content,embedding_text,content_hash,char_count,
                metadata_json,embedding_model_id,embedding_config_revision,ai_config_revision,created_by,updated_by) VALUES (?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?,?,?)
                """,chunks,config.writeBatchSize(),(ps,chunk)->{
            ps.setObject(1,chunk.id());ps.setObject(2,context.kbId());ps.setObject(3,context.documentId());ps.setLong(4,context.generation());
            ps.setString(5,context.actor().tenantId());ps.setString(6,context.actor().userId());ps.setInt(7,chunk.chunkIndex());ps.setString(8,chunk.content());
            ps.setString(9,chunk.embeddingText());ps.setString(10,chunk.contentHash());ps.setInt(11,chunk.charCount());ps.setString(12,encode(chunk.metadata()));
            ps.setString(13,context.binding().modelId());ps.setString(14,context.binding().localRevision());ps.setString(15,embeddings.aiConfigRevision());ps.setString(16,context.actor().userId());ps.setString(17,context.actor().userId());
        });
        var rows=new ArrayList<VectorStore.VectorRecord>();
        for(int i=0;i<chunks.size();i++)rows.add(new VectorStore.VectorRecord(chunks.get(i).id(),context.documentId(),context.generation(),embeddings.vectors().get(i),embeddings.aiConfigRevision(),chunks.get(i).metadata()));
        vectors.write(scope(context),rows);
        jobs.end(context,"PERSIST",chunks.size(),"chunks");jobs.begin(context,"PUBLISH",chunks.size(),"chunks");
        jobs.end(context,"PUBLISH",1,"generations");
        if(jdbc.update("UPDATE kb_ingestion_job SET status='SUCCEEDED',completed_at=clock_timestamp(),lease_owner=NULL,lease_until=NULL,attempt_deadline=NULL,next_run_at=NULL WHERE id=? AND status='RUNNING' AND claim_token=? AND lease_owner=? AND attempts=? AND lease_until>clock_timestamp() AND attempt_deadline>clock_timestamp()",
                context.jobId(),context.claimToken(),context.leaseOwner(),context.attempt())!=1)
            throw new KnowledgeBaseException(409,"KB_JOB_EXPIRED","Ingestion expired before publication");
        jdbc.update("UPDATE kb_document SET active_generation=?,chunk_count=?,has_manual_changes=FALSE,process_status='READY',last_ingested_at=clock_timestamp(),updated_at=clock_timestamp() WHERE id=?",context.generation(),chunks.size(),context.documentId());
        refresh.published(context);
        if(document.get("active_generation") instanceof Number previous && previous.longValue()!=context.generation())
            jobs.queueCleanup(context.documentId(),previous.longValue());
    }
    public static VectorStore.Scope scope(IngestionContext context){return new VectorStore.Scope(context.actor(),context.kbId(),context.namespace(),context.binding().modelId(),context.binding().localRevision());}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}}
}
