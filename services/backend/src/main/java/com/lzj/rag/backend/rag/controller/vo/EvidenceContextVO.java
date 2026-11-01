package com.lzj.rag.backend.rag.controller.vo;

import com.lzj.rag.backend.rag.service.bo.EvidenceContextBO;
import java.util.*;

/** 用户证据视图不包含内部模型消息或系统提示词。 */
public record EvidenceContextVO(List<SnapshotVO> evidence,long estimatedTokens,int inputTokenBudget,boolean estimated,
        boolean truncated,String strategyVersion,String contentHash) {
    public record SnapshotVO(String evidenceId,UUID kbId,UUID documentId,UUID chunkId,long generation,int chunkIndex,
            String documentName,String content,Map<String,Object> sourceMetadata,String fileUrl,String sourceContentHash,String snapshotHash,
            boolean truncated,boolean adjacent,UUID parentChunkId,List<String> subQueries,Double fusionScore,Double rerankScore) {
        public static SnapshotVO from(com.lzj.rag.backend.rag.service.bo.EvidenceSnapshotBO e){return new SnapshotVO(e.evidenceId(),e.kbId(),e.documentId(),e.chunkId(),e.generation(),e.chunkIndex(),
            e.documentName(),e.content(),e.sourceMetadata(),e.fileUrl(),e.sourceContentHash(),e.snapshotHash(),e.truncated(),e.adjacent(),e.parentChunkId(),e.subQueries(),e.fusionScore(),e.rerankScore());}
    }
    public static EvidenceContextVO from(EvidenceContextBO value) {
        if(value==null)return null;
        return new EvidenceContextVO(value.evidence().stream().map(SnapshotVO::from).toList(),
                value.estimatedTokens(),value.inputTokenBudget(),value.estimated(),value.truncated(),value.strategyVersion(),value.contentHash());
    }
}
