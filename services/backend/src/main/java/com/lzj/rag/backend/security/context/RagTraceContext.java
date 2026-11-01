package com.lzj.rag.backend.security.context;

import com.lzj.rag.backend.knowledgebase.exception.KnowledgeBaseException;
import java.util.UUID;

/** W3C 00 传播上下文只用于关联，永远不参与认证或授权。 */
public final class RagTraceContext {
    private static final ThreadLocal<String> CURRENT=new ThreadLocal<>();
    private RagTraceContext() {}
    public static String child(String incoming) {
        if(incoming==null)return "00-"+id()+"-"+id().substring(0,16)+"-01";
        if(!incoming.matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}")
                || incoming.substring(3,35).equals("0".repeat(32)) || incoming.substring(36,52).equals("0".repeat(16)))
            throw new KnowledgeBaseException(400,"RAG_TRACE_INVALID","Invalid traceparent");
        return "00-"+incoming.substring(3,35)+"-"+id().substring(0,16)+"-"+incoming.substring(53);
    }
    public static String current(){return CURRENT.get();}
    public static void set(String value){if(value==null)CURRENT.remove();else CURRENT.set(value);}
    public static Runnable decorate(Runnable task) {
        String snapshot=current();return ()->{String previous=current();set(snapshot);try{task.run();}finally{set(previous);}};
    }
    private static String id(){return UUID.randomUUID().toString().replace("-","");}
}
