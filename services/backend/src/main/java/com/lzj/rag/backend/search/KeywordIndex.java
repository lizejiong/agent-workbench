package com.lzj.rag.backend.search;
import java.time.Instant;
import java.util.*;

public interface KeywordIndex {
    record IndexedChunk(String id,UUID chunkId,UUID kbId,UUID documentId,String tenantId,String ownerId,long generation,long syncRevision,
            String versionKey,String contentHash,String documentName,int chunkIndex,String content,boolean enabled,boolean chunkEnabled,boolean documentEnabled,Map<String,Object> source) { }
    record Hit(UUID chunkId,long syncRevision,String contentHash,int rank,double score) { }
    void ready();
    void write(List<IndexedChunk> chunks,Instant deadline);
    List<Hit> query(String query,String tenant,String owner,List<String> versionKeys,int limit,Instant deadline);
    void deleteBefore(UUID document,long revision,Instant deadline);
}
