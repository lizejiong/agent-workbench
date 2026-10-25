package com.lzj.rag.backend.model;
import java.time.Instant;
import java.util.*;
public interface RerankClient {
    record Input(UUID chunkId,String content) { }
    record Ranked(int index,double score) { }
    List<Ranked> rerank(String query,List<Input> candidates,String requestId,Instant deadline);
}
