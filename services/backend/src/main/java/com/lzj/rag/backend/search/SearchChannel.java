package com.lzj.rag.backend.search;
import com.lzj.rag.backend.security.KnowledgeBasePrincipal;
import java.time.Instant;
import java.util.*;
import static com.lzj.rag.backend.search.KnowledgeSearchTypes.*;

public interface SearchChannel {
    record Candidate(Evidence evidence,String channel,int rank,double rawScore,String scoreType,long syncRevision,String contentHash) { }
    record Result(List<Candidate> candidates,boolean refillLimitReached) { }
    String name();
    Result search(KnowledgeBasePrincipal actor,Request request,String requestId,int limit,Instant deadline);
}
