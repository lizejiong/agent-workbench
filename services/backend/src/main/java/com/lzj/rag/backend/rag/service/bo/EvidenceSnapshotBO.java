package com.lzj.rag.backend.rag.service.bo;

import java.util.*;

public record EvidenceSnapshotBO(String evidenceId,UUID kbId,UUID documentId,UUID chunkId,long generation,int chunkIndex,
        String documentName,String content,Map<String,Object> sourceMetadata,String fileUrl,
        String sourceContentHash,String snapshotHash,boolean truncated,boolean adjacent,
        UUID parentChunkId,List<String> subQueries,Double fusionScore,Double rerankScore) {}
