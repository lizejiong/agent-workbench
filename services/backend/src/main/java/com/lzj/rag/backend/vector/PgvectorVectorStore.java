package com.lzj.rag.backend.vector;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lzj.rag.backend.ingestion.IngestionProperties;
import com.lzj.rag.backend.knowledgebase.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;
import java.util.*;

@Component
public class PgvectorVectorStore implements VectorStore {
    private final JdbcTemplate jdbc;private final KnowledgeBaseService bases;private final ObjectMapper json;private final IngestionProperties config;
    public PgvectorVectorStore(JdbcTemplate jdbc,KnowledgeBaseService bases,ObjectMapper json,IngestionProperties config){this.jdbc=jdbc;this.bases=bases;this.json=json;this.config=config;}
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public void write(Scope scope,List<VectorRecord> records) {
        authorize(scope);
        jdbc.batchUpdate("""
                INSERT INTO kb_vector(chunk_id,kb_id,document_id,generation,tenant_id,owner_id,vector_namespace,embedding_model_id,
                embedding_config_revision,ai_config_revision,embedding,metadata_json) VALUES (?,?,?,?,?,?,?,?,?,?,?::public.vector,?::jsonb)
                """,records,config.writeBatchSize(),(ps,row)->{
            ps.setObject(1,row.chunkId());ps.setObject(2,scope.kbId());ps.setObject(3,row.documentId());ps.setLong(4,row.generation());
            ps.setString(5,scope.actor().tenantId());ps.setString(6,scope.actor().userId());ps.setString(7,scope.namespace());ps.setString(8,scope.modelId());
            ps.setString(9,scope.configRevision());ps.setString(10,row.aiConfigRevision());ps.setString(11,literal(row.embedding()));ps.setString(12,encode(row.metadata()));
        });
    }
    @Override public List<Hit> query(Scope scope,float[] vector,int limit) {
        var base=authorize(scope);literal(vector);checkLimit(limit);
        if(base.getEmbeddingAiConfigRevision()==null)return List.of();
        return query(List.of(scope),vector,base.getEmbeddingAiConfigRevision(),List.of(),limit,null);
    }
    @Override public List<Hit> query(List<Scope> scopes,float[] vector,String aiConfigRevision,List<UUID> documentIds,int limit,Double minScore) {
        checkLimit(limit);
        if(scopes==null || scopes.isEmpty() || scopes.size()>64 || documentIds==null || documentIds.size()>500 || aiConfigRevision==null
                || !aiConfigRevision.matches("[0-9a-f]{64}") || (minScore!=null && (!Double.isFinite(minScore) || minScore< -1 || minScore>1)))
            throw new KnowledgeBaseException(400,"KB_INVALID_INPUT","Invalid vector query scope or score threshold");
        var first=scopes.get(0);var ids=new LinkedHashSet<UUID>();
        for(var scope:scopes) {
            if(scope==null || !first.actor().tenantId().equals(scope.actor().tenantId()) || !first.actor().userId().equals(scope.actor().userId())
                    || !first.modelId().equals(scope.modelId()) || !first.configRevision().equals(scope.configRevision()) || !ids.add(scope.kbId()))
                throw new KnowledgeBaseException(409,"KB_SEARCH_MODEL_MISMATCH","Vector query must use one authorized embedding space");
            var base=authorize(scope);
            if(base.getEmbeddingAiConfigRevision()!=null && !aiConfigRevision.equals(base.getEmbeddingAiConfigRevision()))
                throw new KnowledgeBaseException(409,"KB_MODEL_CONFIG_CHANGED","Query revision does not match the current index");
        }
        var parameters=new MapSqlParameterSource().addValue("tenant",first.actor().tenantId()).addValue("owner",first.actor().userId())
                .addValue("kbIds",ids).addValue("modelId",first.modelId()).addValue("localRevision",first.configRevision()).addValue("aiRevision",aiConfigRevision)
                .addValue("allDocuments",documentIds.isEmpty()).addValue("documentIds",documentIds.isEmpty()?List.of(new UUID(0,0)):documentIds)
                .addValue("vector",literal(vector)).addValue("limit",limit).addValue("minScore",minScore,java.sql.Types.DOUBLE);
        // Exact search: all scope/liveness/generation filters precede ranking and LIMIT. No post-filter recall loss.
        return new NamedParameterJdbcTemplate(jdbc).query("""
                WITH candidates AS MATERIALIZED (
                    SELECT v.embedding,c.id,c.kb_id,c.document_id,d.name AS document_name,c.generation,c.chunk_index,c.content,c.embedding_text,c.metadata_json
                    FROM kb_vector v JOIN kb_chunk c ON c.id=v.chunk_id
                    JOIN kb_document d ON d.id=c.document_id JOIN kb_knowledge_base b ON b.id=d.kb_id
                    JOIN kb_generation g ON g.document_id=c.document_id AND g.generation=c.generation
                    WHERE v.tenant_id=:tenant AND v.owner_id=:owner AND v.kb_id IN (:kbIds)
                    AND b.tenant_id=v.tenant_id AND b.owner_id=v.owner_id AND b.deleted_at IS NULL
                    AND d.deleted_at IS NULL AND d.enabled AND c.enabled AND c.generation=d.active_generation
                    AND d.tenant_id=v.tenant_id AND d.owner_id=v.owner_id AND c.tenant_id=v.tenant_id AND c.owner_id=v.owner_id
                    AND d.kb_id=v.kb_id AND c.kb_id=v.kb_id AND v.document_id=d.id AND v.generation=c.generation
                    AND v.vector_namespace=b.vector_namespace AND v.embedding_model_id=b.embedding_model_id
                    AND v.ai_config_revision=b.embedding_ai_config_revision
                    AND v.ai_config_revision=:aiRevision
                    AND v.embedding_config_revision=b.embedding_config_revision AND c.embedding_config_revision=v.embedding_config_revision
                    AND c.embedding_model_id=v.embedding_model_id AND g.ai_config_revision=v.ai_config_revision AND c.ai_config_revision=v.ai_config_revision
                    AND g.embedding_config_revision=v.embedding_config_revision
                    AND v.embedding_model_id=:modelId AND v.embedding_config_revision=:localRevision
                    AND (:allDocuments OR d.id IN (:documentIds))
                ), scored AS MATERIALIZED (
                    SELECT *,embedding OPERATOR(public.<=>) CAST(:vector AS public.vector) AS distance FROM candidates
                ) SELECT * FROM scored WHERE CAST(:minScore AS double precision) IS NULL
                    OR GREATEST(-1.0,LEAST(1.0,1.0-distance))>=:minScore
                ORDER BY distance,id LIMIT :limit
                """,parameters,(rs,n)->new Hit(rs.getObject("id",UUID.class),rs.getObject("kb_id",UUID.class),rs.getObject("document_id",UUID.class),rs.getString("document_name"),rs.getLong("generation"),rs.getInt("chunk_index"),
                rs.getString("content"),rs.getString("embedding_text"),rs.getString("metadata_json"),rs.getDouble("distance")));
    }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public void deleteByDocument(Scope scope,UUID documentId,long generation) {
        authorize(scope);jdbc.update("DELETE FROM kb_vector WHERE kb_id=? AND tenant_id=? AND owner_id=? AND document_id=? AND generation=?",scope.kbId(),scope.actor().tenantId(),scope.actor().userId(),documentId,generation);
    }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public void deleteByChunk(Scope scope,UUID chunkId) {
        authorize(scope);jdbc.update("DELETE FROM kb_vector WHERE kb_id=? AND tenant_id=? AND owner_id=? AND chunk_id=?",scope.kbId(),scope.actor().tenantId(),scope.actor().userId(),chunkId);
    }
    private KnowledgeBase authorize(Scope scope) {
        var base=bases.requireOwned(scope.actor(),scope.kbId());
        if(!Objects.equals(scope.namespace(),base.getVectorNamespace()) || !Objects.equals(scope.modelId(),base.getEmbeddingModelId()) || !Objects.equals(scope.configRevision(),base.getEmbeddingConfigRevision()))
            throw new KnowledgeBaseException(409,"KB_MODEL_CONFIG_CHANGED","Vector scope does not match the bound knowledge base");
        return base;
    }
    private static void checkLimit(int limit){if(limit<1 || limit>200)throw new KnowledgeBaseException(400,"KB_INVALID_INPUT","Vector result limit must be 1..200");}
    public static String literal(float[] vector) {
        if(vector==null || vector.length!=1024)throw invalid();
        boolean nonzero=false;var text=new StringBuilder("[");
        for(int i=0;i<vector.length;i++) { if(!Float.isFinite(vector[i]))throw invalid();nonzero|=vector[i]!=0;if(i>0)text.append(',');text.append(Float.toString(vector[i])); }
        if(!nonzero)throw invalid();return text.append(']').toString();
    }
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}}
    private static KnowledgeBaseException invalid(){return new KnowledgeBaseException(502,"KB_MODEL_INVALID_RESPONSE","A finite nonzero 1024-dimensional vector is required");}
}
