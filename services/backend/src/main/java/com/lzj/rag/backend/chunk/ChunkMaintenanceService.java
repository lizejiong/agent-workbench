package com.lzj.rag.backend.chunk;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lzj.rag.backend.document.*;
import com.lzj.rag.backend.ingestion.*;
import com.lzj.rag.backend.knowledgebase.*;
import com.lzj.rag.backend.model.*;
import com.lzj.rag.backend.security.KnowledgeBasePrincipal;
import com.lzj.rag.backend.vector.VectorStore;
import com.lzj.rag.framework.convention.page.PageResponse;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import static com.lzj.rag.backend.chunk.MaintenanceTypes.*;

/** HTTP/model work never holds row locks. Each mutation rechecks the document version under the publication locks. */
@Service @EnableConfigurationProperties(MaintenanceProperties.class)
@Transactional(propagation=Propagation.NOT_SUPPORTED)
public class ChunkMaintenanceService {
    private final DocumentService documents;private final DocumentMapper documentMapper;private final StoredChunkMapper chunks;
    private final KnowledgeBaseService bases;private final IngestionJobService jobs;private final JdbcTemplate jdbc;
    private final EmbeddingClient embeddings;private final VectorStore vectors;private final ObjectMapper json;
    private final MaintenanceProperties config;private final IngestionProperties ingestion;private final TransactionTemplate tx;
    private final com.lzj.rag.backend.refresh.RefreshState refresh;
    public ChunkMaintenanceService(DocumentService documents,DocumentMapper documentMapper,StoredChunkMapper chunks,KnowledgeBaseService bases,
            IngestionJobService jobs,JdbcTemplate jdbc,EmbeddingClient embeddings,VectorStore vectors,ObjectMapper json,
            MaintenanceProperties config,IngestionProperties ingestion,PlatformTransactionManager transactions,com.lzj.rag.backend.refresh.RefreshState refresh) {
        this.documents=documents;this.documentMapper=documentMapper;this.chunks=chunks;this.bases=bases;this.jobs=jobs;this.jdbc=jdbc;
        this.embeddings=embeddings;this.vectors=vectors;this.json=json;this.config=config;this.ingestion=ingestion;
        tx=new TransactionTemplate(transactions);tx.setTimeout(30);
        this.refresh=refresh;
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,timeout=10)
    public PageResponse<ChunkView> list(KnowledgeBasePrincipal actor,UUID id,int current,int size,Boolean enabled) {
        var doc=documents.requireOwned(actor,id);var filter=filter(actor,doc);
        if(enabled!=null)filter.eq(StoredChunk::getEnabled,enabled);
        long total=chunks.selectCount(filter);
        var rows=chunks.selectList(filter.orderByAsc(StoredChunk::getChunkIndex,StoredChunk::getId)
                .last("LIMIT "+size+" OFFSET "+(((long)current-1)*size)));
        return new PageResponse<>(current,size,total,rows.stream().map(this::view).toList());
    }
    public Changed save(KnowledgeBasePrincipal actor,UUID id,UUID chunkId,ContentRequest request) {
        var snapshot=snapshot(actor,id,request.version(),true,true);
        if(snapshot.getActiveGeneration()==null)throw new KnowledgeBaseException(409,"KB_DOCUMENT_NOT_INDEXED","Publish the first ingestion before maintaining chunks");
        StoredChunk previous=chunkId==null?null:requireChunk(actor,snapshot,chunkId);
        String content=content(request.content());var base=bases.requireOwned(actor,snapshot.getKbId());
        var result=embed(base,List.of(content));
        return tx.execute(status->{
            var doc=locked(actor,snapshot,request.version(),true,true);var before=summary(doc);
            StoredChunk chunk=previous==null?new StoredChunk():requireChunk(actor,doc,chunkId);
            var now=OffsetDateTime.now(ZoneOffset.UTC);
            if(previous==null) {
                if(doc.getChunkCount()>=ingestion.maxChunks())throw invalid("Document chunk limit reached");
                chunk.setId(UUID.randomUUID());chunk.setDocumentId(id);chunk.setKbId(doc.getKbId());chunk.setGeneration(doc.getActiveGeneration());
                chunk.setTenantId(actor.tenantId());chunk.setOwnerId(actor.userId());chunk.setEnabled(true);
                int index=jdbc.queryForObject("UPDATE kb_generation SET next_chunk_index=next_chunk_index+1 WHERE document_id=? AND generation=? RETURNING next_chunk_index-1",
                        Integer.class,id,doc.getActiveGeneration());
                chunk.setChunkIndex(index);chunk.setCreatedBy(actor.userId());chunk.setCreatedAt(now);
            }
            var old=previous==null?Map.of():chunkSummary(chunk);
            chunk.setContent(content);chunk.setEmbeddingText(content);chunk.setContentHash(EmbeddingCatalog.sha256(content));
            chunk.setCharCount(content.codePointCount(0,content.length()));chunk.setTokenCount(estimatedTokens(content));chunk.setTokenCountEstimated(true);
            chunk.setEdited(true);chunk.setMetadataJson(encode(manualMetadata()));chunk.setEmbeddingModelId(base.getEmbeddingModelId());
            chunk.setEmbeddingConfigRevision(base.getEmbeddingConfigRevision());chunk.setAiConfigRevision(result.aiConfigRevision());
            chunk.setUpdatedBy(actor.userId());chunk.setUpdatedAt(now);
            if(previous==null)chunks.insert(chunk);else {vectors.deleteByChunk(scope(actor,base),chunk.getId());chunks.updateById(chunk);}
            vectors.write(scope(actor,base),List.of(record(chunk,result.vectors().get(0),manualMetadata())));
            touch(actor,doc,true);
            audit(actor,doc,previous==null?"CHUNK_CREATE":"CHUNK_EDIT",List.of(chunk.getId()),Map.of("document",before,"chunk",old),
                    Map.of("document",summary(doc),"chunk",chunkSummary(chunk)));
            return changed(doc,List.of(chunk));
        });
    }
    public Changed delete(KnowledgeBasePrincipal actor,UUID id,UUID chunkId,long version) {
        return tx.execute(status->{
            var doc=locked(actor,null,id,version,false,true);var before=summary(doc);var chunk=requireChunk(actor,doc,chunkId);
            vectors.deleteByChunk(scope(actor,bases.requireOwned(actor,doc.getKbId())),chunkId);chunks.deleteById(chunkId);
            touch(actor,doc,true);audit(actor,doc,"CHUNK_DELETE",List.of(chunkId),Map.of("document",before,"chunk",chunkSummary(chunk)),summary(doc));
            return changed(doc,List.of());
        });
    }
    public Changed enabled(KnowledgeBasePrincipal actor,UUID id,List<UUID> chunkIds,boolean applyAll,boolean enabled,long version) {
        if((applyAll && chunkIds!=null) || (!applyAll && (chunkIds==null || chunkIds.isEmpty() || chunkIds.size()>config.maxBatchIds()
                || chunkIds.stream().anyMatch(Objects::isNull) || new HashSet<>(chunkIds).size()!=chunkIds.size())))
            throw invalid("Choose nonempty unique chunkIds within the limit, or applyAll=true with chunkIds omitted");
        var snapshot=snapshot(actor,id,version,enabled,true);var selected=select(actor,snapshot,chunkIds,applyAll);
        var missing=enabled?missing(selected):List.<StoredChunk>of();var base=bases.requireOwned(actor,snapshot.getKbId());
        var result=missing.isEmpty()?null:embed(base,missing.stream().map(StoredChunk::getEmbeddingText).toList());
        return tx.execute(status->{
            var doc=locked(actor,snapshot,version,enabled,true);var before=summary(doc);var rows=select(actor,doc,chunkIds,applyAll);
            var old=rows.stream().map(this::chunkSummary).toList();
            restore(actor,base,missing,result);
            for(var chunk:rows){chunk.setEnabled(enabled);chunk.setUpdatedBy(actor.userId());chunk.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));chunks.updateById(chunk);}
            touch(actor,doc,true);audit(actor,doc,enabled?"CHUNK_ENABLE":"CHUNK_DISABLE",rows.stream().map(StoredChunk::getId).toList(),
                    Map.of("document",before,"chunks",old),Map.of("document",summary(doc),"chunks",rows.stream().map(this::chunkSummary).toList()));
            return changed(doc,rows);
        });
    }
    public DocumentTypes.View documentEnabled(KnowledgeBasePrincipal actor,UUID id,boolean enabled,long version) {
        var snapshot=snapshot(actor,id,version,false,false);
        var selected=enabled?chunks.selectList(filter(actor,snapshot).eq(StoredChunk::getEnabled,true)):List.<StoredChunk>of();
        var missing=missing(selected);var base=bases.requireOwned(actor,snapshot.getKbId());
        var result=missing.isEmpty()?null:embed(base,missing.stream().map(StoredChunk::getEmbeddingText).toList());
        return tx.execute(status->{
            var doc=locked(actor,snapshot,version,false,false);var before=summary(doc);
            cancelActive(actor,id);restore(actor,base,missing,result);
            if(!enabled)refresh.stopDocument(id);
            if("PROCESSING".equals(doc.getProcessStatus()))doc.setProcessStatus("PENDING");
            doc.setEnabled(enabled);touch(actor,doc,false);
            audit(actor,doc,enabled?"DOCUMENT_ENABLE":"DOCUMENT_DISABLE",List.of(),before,summary(doc));return documents.view(doc);
        });
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void cancelActive(KnowledgeBasePrincipal actor,UUID id) {
        for(UUID job:jdbc.queryForList("SELECT id FROM kb_ingestion_job WHERE document_id=? AND status IN ('PENDING','RUNNING') ORDER BY id",UUID.class,id))jobs.cancel(actor,job);
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void audit(KnowledgeBasePrincipal actor,Document doc,String action,List<UUID> ids,Object before,Object after) {
        jdbc.update("""
                INSERT INTO kb_content_audit(id,document_id,kb_id,tenant_id,owner_id,actor_id,action,chunk_ids,before_summary,after_summary)
                VALUES (?,?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb)
                """,UUID.randomUUID(),doc.getId(),doc.getKbId(),actor.tenantId(),actor.userId(),actor.userId(),action,encode(ids),encode(before),encode(after));
    }
    public Map<String,Object> summary(Document doc) {
        return Map.of("version",doc.getVersion(),"manualRevision",doc.getManualRevision(),"chunkCount",doc.getChunkCount(),"enabled",doc.getEnabled());
    }
    private Map<String,Object> chunkSummary(StoredChunk c){return Map.of("id",c.getId(),"hash",c.getContentHash(),"charCount",c.getCharCount(),"enabled",c.getEnabled(),"edited",c.getEdited());}
    private Document snapshot(KnowledgeBasePrincipal actor,UUID id,long version,boolean requireEnabled,boolean rejectJob) {
        var doc=documents.requireOwned(actor,id);check(doc,version,requireEnabled,rejectJob);return doc;
    }
    private Document locked(KnowledgeBasePrincipal actor,Document snapshot,long version,boolean requireEnabled,boolean rejectJob) {
        return locked(actor,snapshot,snapshot.getId(),version,requireEnabled,rejectJob);
    }
    private Document locked(KnowledgeBasePrincipal actor,Document snapshot,UUID id,long version,boolean requireEnabled,boolean rejectJob) {
        var ids=jdbc.queryForList("SELECT kb_id FROM kb_document WHERE id=? AND tenant_id=? AND owner_id=? AND deleted_at IS NULL",UUID.class,id,actor.tenantId(),actor.userId());
        if(ids.isEmpty())throw new KnowledgeBaseException(404,"KB_DOCUMENT_NOT_FOUND","Document not found");
        bases.lockOwned(actor,ids.get(0));
        jdbc.queryForObject("SELECT id FROM kb_document WHERE id=? FOR UPDATE",UUID.class,id);
        var doc=documents.lockOwned(actor,id);check(doc,version,requireEnabled,rejectJob);
        if(snapshot!=null && !Objects.equals(snapshot.getActiveGeneration(),doc.getActiveGeneration()))throw conflict();return doc;
    }
    private void check(Document doc,long version,boolean requireEnabled,boolean rejectJob) {
        if(doc.getVersion()!=version)throw conflict();
        if(requireEnabled && !doc.getEnabled())throw new KnowledgeBaseException(409,"KB_DOCUMENT_DISABLED","Enable the document first");
        if(rejectJob && Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM kb_refresh_run WHERE document_id=? AND status IN ('PENDING','RUNNING','WAITING_INGESTION'))",Boolean.class,doc.getId())))
            throw new KnowledgeBaseException(409,"KB_REFRESH_ACTIVE","Wait for refresh before maintaining chunks");
        if(rejectJob && Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM kb_ingestion_job WHERE document_id=? AND status IN ('PENDING','RUNNING'))",Boolean.class,doc.getId())))
            throw new KnowledgeBaseException(409,"KB_JOB_ACTIVE","Wait for ingestion or cancel it before maintaining chunks");
    }
    private LambdaQueryWrapper<StoredChunk> filter(KnowledgeBasePrincipal actor,Document doc) {
        return new LambdaQueryWrapper<StoredChunk>().eq(StoredChunk::getDocumentId,doc.getId()).eq(StoredChunk::getKbId,doc.getKbId())
                .eq(StoredChunk::getTenantId,actor.tenantId()).eq(StoredChunk::getOwnerId,actor.userId())
                .eq(StoredChunk::getGeneration,doc.getActiveGeneration()==null?-1:doc.getActiveGeneration());
    }
    private StoredChunk requireChunk(KnowledgeBasePrincipal actor,Document doc,UUID id) {
        var chunk=chunks.selectOne(filter(actor,doc).eq(StoredChunk::getId,id));
        if(chunk==null)throw new KnowledgeBaseException(404,"KB_CHUNK_NOT_FOUND","Current document chunk not found");return chunk;
    }
    private List<StoredChunk> select(KnowledgeBasePrincipal actor,Document doc,List<UUID> ids,boolean all) {
        if(doc.getActiveGeneration()==null)throw new KnowledgeBaseException(409,"KB_DOCUMENT_NOT_INDEXED","Publish the first ingestion before maintaining chunks");
        var filter=filter(actor,doc);if(!all)filter.in(StoredChunk::getId,ids);
        var rows=chunks.selectList(filter.orderByAsc(StoredChunk::getChunkIndex,StoredChunk::getId));
        if(!all && rows.size()!=ids.size())throw new KnowledgeBaseException(404,"KB_CHUNK_NOT_FOUND","Every chunk must belong to the current document generation");return rows;
    }
    private List<StoredChunk> missing(List<StoredChunk> rows) {
        if(rows.isEmpty())return List.of();
        // At most maxChunks rows; a single bound collection query avoids N+1 and never accepts client SQL.
        var params=new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("ids",rows.stream().map(StoredChunk::getId).toList());
        var found=new HashSet<>(new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc)
                .queryForList("SELECT chunk_id FROM kb_vector WHERE chunk_id IN (:ids)",params,UUID.class));
        return rows.stream().filter(c->!found.contains(c.getId())).toList();
    }
    private void restore(KnowledgeBasePrincipal actor,KnowledgeBase base,List<StoredChunk> missing,EmbeddingClient.Result result) {
        if(missing.isEmpty())return;
        var records=new ArrayList<VectorStore.VectorRecord>();
        for(int i=0;i<missing.size();i++)records.add(record(missing.get(i),result.vectors().get(i),metadata(missing.get(i))));
        vectors.write(scope(actor,base),records);
    }
    private EmbeddingClient.Result embed(KnowledgeBase base,List<String> texts) {
        if(base.getEmbeddingAiConfigRevision()==null)throw new KnowledgeBaseException(409,"KB_DOCUMENT_NOT_INDEXED","Publish the first ingestion before maintaining chunks");
        try {
            var model=json.readTree(base.getEmbeddingConfigJson());
            var binding=new EmbeddingClient.Binding(base.getEmbeddingModelId(),base.getEmbeddingProvider(),model.path("modelName").asText(),base.getEmbeddingDimension(),
                    model.path("embeddingSpace").asText(),model.path("configVersion").asText(),base.getEmbeddingConfigRevision(),base.getEmbeddingAiConfigRevision());
            var result=embeddings.embed(binding,texts,"maintenance-"+UUID.randomUUID(),Instant.now().plus(config.modelDeadline()));
            if(result==null || result.vectors()==null || result.vectors().size()!=texts.size() || !binding.expectedAiRevision().equals(result.aiConfigRevision()))
                throw new KnowledgeBaseException(502,"KB_MODEL_INVALID_RESPONSE","Maintenance embeddings do not match the current index");
            return result;
        }catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("Invalid persisted model binding",e);}
    }
    private void touch(KnowledgeBasePrincipal actor,Document doc,boolean manual) {
        long count=chunks.selectCount(filter(actor,doc));doc.setChunkCount(count);
        if(doc.getActiveGeneration()!=null)jdbc.update("UPDATE kb_generation SET chunk_count=? WHERE document_id=? AND generation=?",count,doc.getId(),doc.getActiveGeneration());
        if(manual){doc.setHasManualChanges(true);doc.setManualRevision(doc.getManualRevision()+1);}
        doc.setVersion(doc.getVersion()+1);doc.setUpdatedBy(actor.userId());doc.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));documentMapper.updateById(doc);
    }
    private Changed changed(Document d,List<StoredChunk> rows){return new Changed(d.getVersion(),d.getManualRevision(),d.getChunkCount(),rows.stream().map(this::view).toList());}
    private ChunkView view(StoredChunk c){return new ChunkView(c.getId(),c.getDocumentId(),c.getKbId(),c.getGeneration(),c.getChunkIndex(),c.getContent(),c.getEmbeddingText(),
            c.getContentHash(),c.getCharCount(),c.getTokenCount(),c.getTokenCountEstimated(),c.getEnabled(),c.getEdited(),metadata(c),c.getCreatedBy(),c.getUpdatedBy(),c.getCreatedAt(),c.getUpdatedAt());}
    private Map<String,Object> metadata(StoredChunk c){try{return json.readValue(c.getMetadataJson(),new TypeReference<Map<String,Object>>(){});}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}}
    private Map<String,Object> manualMetadata(){return Map.of("blockType","MANUAL","manual",true,"originalFileContainsContent",false,"chapterPath",List.of(),"sourceBlocks",List.of());}
    private VectorStore.VectorRecord record(StoredChunk c,float[] vector,Map<String,Object> metadata){return new VectorStore.VectorRecord(c.getId(),c.getDocumentId(),c.getGeneration(),vector,c.getAiConfigRevision(),metadata);}
    private VectorStore.Scope scope(KnowledgeBasePrincipal actor,KnowledgeBase b){return new VectorStore.Scope(actor,b.getId(),b.getVectorNamespace(),b.getEmbeddingModelId(),b.getEmbeddingConfigRevision());}
    private String content(String raw) {
        if(raw==null)throw invalid("Content is required");String value=raw.replace("\r\n","\n").replace('\r','\n');
        if(value.isBlank() || value.codePointCount(0,value.length())>Math.min(ingestion.hardChunkChars(),ingestion.maxEmbeddingChars())
                || value.codePoints().anyMatch(c->c==0 || (c>=0xd800 && c<=0xdfff)))throw invalid("Content is blank, invalid, or exceeds the configured chunk budget");
        return value;
    }
    private int estimatedTokens(String value){long ascii=value.codePoints().filter(c->c<128).count();return Math.max(1,(int)((ascii+3)/4+value.codePointCount(0,value.length())-ascii));}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}}
    private static KnowledgeBaseException invalid(String m){return new KnowledgeBaseException(400,"KB_INVALID_INPUT",m);}
    private static KnowledgeBaseException conflict(){return new KnowledgeBaseException(409,"KB_VERSION_CONFLICT","Reload the document version and current generation before retrying");}
}
