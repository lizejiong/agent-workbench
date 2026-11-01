package com.lzj.rag.backend.rag.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lzj.rag.backend.knowledgebase.exception.KnowledgeBaseException;
import com.lzj.rag.backend.rag.service.bo.*;
import com.lzj.rag.backend.security.model.KnowledgeBasePrincipal;
import org.springframework.dao.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

/** 短事务只负责运行与结果；检索和模型网络调用均在事务外。 */
@Repository
public class AnswerLedger {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public AnswerLedger(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}

    @Transactional(timeout=10)
    public AnswerRunBO begin(KnowledgeBasePrincipal actor,UUID requestedConversation,String question,String requestId,Instant deadline) {
        UUID conversation=requestedConversation;
        if(conversation==null) {
            conversation=UUID.randomUUID();
            jdbc.update("INSERT INTO kb_rag_conversation(id,tenant_id,owner_id) VALUES(?,?,?)",conversation,actor.tenantId(),actor.userId());
        }
        var owned=jdbc.queryForList("SELECT id FROM kb_rag_conversation WHERE id=? AND tenant_id=? AND owner_id=? FOR UPDATE",
                UUID.class,conversation,actor.tenantId(),actor.userId());
        if(owned.isEmpty()) throw notFound("RAG_CONVERSATION_NOT_FOUND");
        expire(conversation);
        UUID id=UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO kb_rag_answer_run(id,conversation_id,tenant_id,owner_id,request_id,question,status,deadline_at) VALUES(?,?,?,?,?,?,'RUNNING',?)",
                    id,conversation,actor.tenantId(),actor.userId(),requestId,question,java.sql.Timestamp.from(deadline));
        } catch(DuplicateKeyException busy) {
            throw new KnowledgeBaseException(409,"RAG_CONVERSATION_BUSY","A turn is already running in this conversation");
        }
        return get(actor,id);
    }
    public void requireConversation(KnowledgeBasePrincipal actor,UUID id) {
        if(id!=null && jdbc.queryForObject("SELECT count(*) FROM kb_rag_conversation WHERE id=? AND tenant_id=? AND owner_id=?",
                Integer.class,id,actor.tenantId(),actor.userId())!=1) throw notFound("RAG_CONVERSATION_NOT_FOUND");
    }
    public AnswerRunBO get(KnowledgeBasePrincipal actor,UUID id) {
        var rows=jdbc.query("SELECT * FROM kb_rag_answer_run WHERE id=? AND tenant_id=? AND owner_id=?",this::row,id,actor.tenantId(),actor.userId());
        if(rows.isEmpty()) throw notFound("RAG_RUN_NOT_FOUND");return rows.get(0);
    }
    public List<AnswerRunBO> recent(KnowledgeBasePrincipal actor,UUID conversation,int limit) {
        requireConversation(actor,conversation);
        if(conversation==null)return List.of();
        return jdbc.query("SELECT * FROM kb_rag_answer_run WHERE conversation_id=? AND tenant_id=? AND owner_id=? ORDER BY created_at DESC,id DESC LIMIT ?",
                this::row,conversation,actor.tenantId(),actor.userId(),Math.min(20,Math.max(1,limit)));
    }
    public void finish(KnowledgeBasePrincipal actor,UUID id,RagAnswerBO result) {
        int changed=jdbc.update("UPDATE kb_rag_answer_run SET status=?,answer_text=?,result_json=?::jsonb,error_code=?,updated_at=clock_timestamp() "
                        +"WHERE id=? AND tenant_id=? AND owner_id=? AND status='RUNNING' AND deadline_at>clock_timestamp()",
                result.status(),result.answer(),encode(result),result.errorCode(),id,actor.tenantId(),actor.userId());
        if(changed!=1) throw new KnowledgeBaseException(409,"RAG_RUN_ENDED","Answer run already ended or expired");
    }
    public void fail(KnowledgeBasePrincipal actor,UUID id,String status,String code,String partial) {
        jdbc.update("UPDATE kb_rag_answer_run SET status=?,error_code=?,answer_text=?,updated_at=clock_timestamp() WHERE id=? AND tenant_id=? AND owner_id=? AND status='RUNNING'",
                status,code,partial,id,actor.tenantId(),actor.userId());
    }
    public void expire(UUID conversation) {
        jdbc.update("UPDATE kb_rag_answer_run SET status='TIMED_OUT',error_code='RAG_RUN_EXPIRED',updated_at=clock_timestamp() "
                +"WHERE conversation_id=? AND status='RUNNING' AND deadline_at<=clock_timestamp()",conversation);
    }
    /** 全局截止时间清扫也恢复进程崩溃后遗留的 RUNNING；不会重放生成。 */
    public void expireAll() {
        jdbc.update("UPDATE kb_rag_answer_run SET status='TIMED_OUT',error_code='RAG_RUN_EXPIRED',updated_at=clock_timestamp() "
                +"WHERE status='RUNNING' AND deadline_at<=clock_timestamp()");
    }
    public AnswerRunBO cancel(KnowledgeBasePrincipal actor,UUID id,String status,String code) {
        get(actor,id);
        jdbc.update("UPDATE kb_rag_answer_run SET status=?,error_code=?,updated_at=clock_timestamp() WHERE id=? AND tenant_id=? AND owner_id=? AND status='RUNNING'",
                status,code,id,actor.tenantId(),actor.userId());
        return get(actor,id);
    }
    public void partial(KnowledgeBasePrincipal actor,UUID id,String text) {
        jdbc.update("UPDATE kb_rag_answer_run SET answer_text=?,updated_at=clock_timestamp() WHERE id=? AND tenant_id=? AND owner_id=? "
                +"AND status IN ('RUNNING','CANCELLED','TIMED_OUT')",text,id,actor.tenantId(),actor.userId());
    }
    public void checkpoint(KnowledgeBasePrincipal actor,UUID id,RagAnswerBO snapshot) {
        jdbc.update("UPDATE kb_rag_answer_run SET result_json=?::jsonb,updated_at=clock_timestamp() WHERE id=? AND tenant_id=? AND owner_id=? AND status='RUNNING'",
                encode(snapshot),id,actor.tenantId(),actor.userId());
    }
    private AnswerRunBO row(java.sql.ResultSet rs,int row)throws java.sql.SQLException {
        String value=rs.getString("result_json");RagAnswerBO result=null;
        if(value!=null) try{result=json.readValue(value,RagAnswerBO.class);}catch(java.io.IOException e){throw new IllegalStateException("Invalid saved answer",e);}
        // 列状态是 CAS 结算依据；失败/取消和周期性部分文本沿用最后一次已落库的证据快照。
        if(result!=null) {
            String status=rs.getString("status");boolean complete=Set.of("COMPLETED","CITATION_INVALID","NO_EVIDENCE","NEEDS_CLARIFICATION").contains(status);
            var r=result;
            result=new RagAnswerBO(r.answerId(),r.conversationId(),r.requestId(),status,rs.getString("answer_text"),complete?r.citations():List.of(),r.sources(),
                    r.degraded() || !complete,complete?r.citationValidation():"NOT_APPLICABLE",r.usage(),r.modelCalls(),r.query(),r.retrievalTrace(),r.evidenceContextHash(),r.repairAttempted(),rs.getString("error_code"));
        }
        return new AnswerRunBO(rs.getObject("id",UUID.class),rs.getObject("conversation_id",UUID.class),rs.getString("request_id"),rs.getString("question"),rs.getString("status"),
                rs.getTimestamp("deadline_at").toInstant(),rs.getString("answer_text"),result,rs.getString("error_code"),rs.getTimestamp("created_at").toInstant(),rs.getTimestamp("updated_at").toInstant());
    }
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(java.io.IOException e){throw new IllegalStateException("Cannot save answer",e);}}
    private KnowledgeBaseException notFound(String code){return new KnowledgeBaseException(404,code,"Resource not found");}
}
