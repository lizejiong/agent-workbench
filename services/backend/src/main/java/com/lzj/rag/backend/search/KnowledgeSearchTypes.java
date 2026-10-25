package com.lzj.rag.backend.search;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.*;
import java.util.*;

public final class KnowledgeSearchTypes {
    private KnowledgeSearchTypes(){ }
    public record Request(@NotBlank @Size(max=32000) @Pattern(regexp="[^\\x00]*") String query,
            @NotEmpty @Size(max=64) List<@NotNull UUID> kbIds,
            @Size(min=1,max=500) List<@NotNull UUID> documentIds,
            @Min(1) Integer topK,Double minScore,@Pattern(regexp="AUTO|VECTOR|HYBRID|KEYWORD") String strategy,Boolean rerank,Double minRerankScore) {
        public Request(String query,List<UUID> kbIds,List<UUID> documentIds,Integer topK,Double minScore){this(query,kbIds,documentIds,topK,minScore,null,null,null);}
        public Request {if(topK==null)topK=5;}
        @JsonIgnore @AssertTrue(message="minScore must be a finite cosine similarity in [-1,1]")
        public boolean isMinScoreValid(){return minScore==null || (Double.isFinite(minScore) && minScore>=-1 && minScore<=1);}
    }
    public record Evidence(UUID chunkId,UUID kbId,UUID documentId,String documentName,int chunkIndex,long generation,
            String content,double score,String scoreType,Map<String,Object> sourceMetadata,String fileUrl,
            Double fusionScore,Double rerankScore,List<ChannelScore> channelScores) {
        public Evidence(UUID chunkId,UUID kbId,UUID documentId,String documentName,int chunkIndex,long generation,String content,double score,String scoreType,Map<String,Object> sourceMetadata,String fileUrl){this(chunkId,kbId,documentId,documentName,chunkIndex,generation,content,score,scoreType,sourceMetadata,fileUrl,null,null,List.of(new ChannelScore("VECTOR",1,score,"COSINE_SIMILARITY")));}
    }
    public record ChannelScore(String channel,int rank,double score,String scoreType) { }
    public record ChannelState(String channel,String status,int candidates,String errorCode) { }
    public record Retrieval(List<Evidence> evidence,String requestedStrategy,String strategy,String rerankStatus,
            List<ChannelState> channels,int channelCandidates,int fusionCandidates,int topK,boolean refillLimitReached,Double minRerankScore) { }
}
