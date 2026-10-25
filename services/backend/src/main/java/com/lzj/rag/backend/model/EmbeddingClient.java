package com.lzj.rag.backend.model;

import java.util.List;
import java.time.Instant;

public interface EmbeddingClient {
    record Binding(String modelId,String provider,String modelName,int dimension,String embeddingSpace,String configVersion,String localRevision,String expectedAiRevision) {
        public Binding(String modelId,String provider,String modelName,int dimension,String embeddingSpace,String configVersion,String localRevision){this(modelId,provider,modelName,dimension,embeddingSpace,configVersion,localRevision,null);}
    }
    record Result(List<float[]> vectors,String aiConfigRevision) { }
    Result embed(Binding binding,List<String> texts,String requestId,Instant deadline);
}
