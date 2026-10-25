package com.lzj.rag.backend.vector;

import com.lzj.rag.backend.security.KnowledgeBasePrincipal;
import java.util.*;

public interface VectorStore {
    record Scope(KnowledgeBasePrincipal actor,UUID kbId,String namespace,String modelId,String configRevision) { }
    record VectorRecord(UUID chunkId,UUID documentId,long generation,float[] embedding,String aiConfigRevision,Map<String,Object> metadata) { }
    record Hit(UUID chunkId,UUID kbId,UUID documentId,String documentName,long generation,int chunkIndex,String content,String embeddingText,String metadataJson,double cosineDistance) { }
    void write(Scope scope,List<VectorRecord> records);
    List<Hit> query(Scope scope,float[] query,int limit);
    List<Hit> query(List<Scope> scopes,float[] query,String aiConfigRevision,List<UUID> documentIds,int limit,Double minScore);
    void deleteByDocument(Scope scope,UUID documentId,long generation);
    void deleteByChunk(Scope scope,UUID chunkId);
}
