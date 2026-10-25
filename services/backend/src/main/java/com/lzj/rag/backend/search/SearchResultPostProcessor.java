package com.lzj.rag.backend.search;
import com.lzj.rag.backend.security.KnowledgeBasePrincipal;
import java.util.*;
import static com.lzj.rag.backend.search.KnowledgeSearchTypes.*;
public interface SearchResultPostProcessor {
    record Result(List<Evidence> evidence,String rerankStatus) { }
    Result process(KnowledgeBasePrincipal actor,Request request,List<SearchChannel.Candidate> candidates,String requestId,boolean rerank,int fusionLimit);
}
