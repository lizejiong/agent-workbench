package com.lzj.rag.backend.search;
import com.lzj.rag.backend.security.KnowledgeBasePrincipal;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.*;

@Component
public class VectorSearchChannel implements SearchChannel {
    private final KnowledgeSearchService service;
    public VectorSearchChannel(KnowledgeSearchService service){this.service=service;}
    public String name(){return "VECTOR";}
    public Result search(KnowledgeBasePrincipal actor,KnowledgeSearchTypes.Request request,String id,int limit,Instant deadline){var rows=service.recall(actor,request,id,limit);var result=new ArrayList<Candidate>();int rank=0;for(var row:rows)result.add(new Candidate(row,name(),++rank,row.score(),row.scoreType(),0,null));return new Result(result,false);}
}
