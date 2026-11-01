package com.lzj.rag.backend.rag.controller.vo;

import com.lzj.rag.backend.rag.service.bo.RagRetrievalBO;
import com.lzj.rag.backend.search.service.bo.IndexVersionBO;
import java.util.*;

/** Agent 白名单视图；完整查询调试、提示词、HTTP 地址与异常不进入工具结果。 */
public record AgentEvidenceVO(String status,String requestId,String traceId,String clarificationQuestion,
        boolean degraded,String strategy,String rerankStatus,List<EvidenceContextVO.SnapshotVO> evidence,
        Map<String,Score> scores,String contextHash,Versions versions) {
    public record Score(double score,String scoreType,Double fusionScore,Double rerankScore) {}
    public record Versions(String pipeline,String context,String queryPrompt,String queryPromptHash,List<IndexVersionBO> indexes) {}
    public static AgentEvidenceVO from(RagRetrievalBO r,String requestId,String traceId) {
        var context=r.context();var retrieval=r.retrieval();
        Map<String,Score> scores=new LinkedHashMap<>();
        if(context!=null && retrieval!=null)for(var source:context.evidence())retrieval.evidence().stream().filter(e->e.chunkId().equals(source.chunkId())).findFirst()
                .ifPresent(e->scores.put(source.evidenceId(),new Score(e.score(),e.scoreType(),e.fusionScore(),e.rerankScore())));
        return new AgentEvidenceVO(r.status(),requestId,traceId,r.query().clarificationQuestion(),r.query().degraded() || retrieval!=null && retrieval.status().equals("DEGRADED"),
                retrieval==null?"NONE":retrieval.strategy(),retrieval==null?"DISABLED":retrieval.rerankStatus(),context==null?List.of():context.evidence().stream().map(EvidenceContextVO.SnapshotVO::from).toList(),
                Map.copyOf(scores),context==null?null:context.contentHash(),new Versions(retrieval==null?null:retrieval.trace().pipelineVersion(),context==null?null:context.strategyVersion(),
                        r.query().promptVersion(),r.query().promptHash(),retrieval==null?List.of():retrieval.trace().indexes()));
    }
}
