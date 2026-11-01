package com.lzj.rag.backend.rag.repository;

import com.lzj.rag.backend.knowledgebase.exception.KnowledgeBaseException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.UUID;

/** 跨实例取消与取消先到的墓碑；没有 Agent 消息、问题正文或图状态。 */
@Repository
public class ToolRequestRepository {
    private final JdbcTemplate jdbc;
    public ToolRequestRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public void begin(String requestId,UUID grant,String trace,Instant deadline) {
        int inserted=jdbc.update("INSERT INTO kb_rag_tool_request(request_id,delegation_id,trace_id,deadline_at,status) VALUES(?,?,?,?,'RUNNING') ON CONFLICT DO NOTHING",
                requestId,grant,trace,java.sql.Timestamp.from(deadline));
        if(inserted!=1)throw new KnowledgeBaseException(409,"RAG_TOOL_REQUEST_REUSED","Each tool attempt requires a new requestId");
    }
    public String status(String requestId,UUID grant) {
        var states=jdbc.queryForList("SELECT CASE WHEN status='RUNNING' AND deadline_at<=clock_timestamp() THEN 'TIMED_OUT' ELSE status END FROM kb_rag_tool_request WHERE request_id=? AND delegation_id=?",
                String.class,requestId,grant);return states.isEmpty()?"CANCELLED":states.get(0);
    }
    public boolean finish(String id,UUID grant) {
        return jdbc.update("UPDATE kb_rag_tool_request SET status='FINISHED' WHERE request_id=? AND delegation_id=? AND status='RUNNING' AND deadline_at>clock_timestamp()",id,grant)==1;
    }
    public void stop(String id,UUID grant,String trace,Instant deadline,String status) {
        jdbc.update("INSERT INTO kb_rag_tool_request(request_id,delegation_id,trace_id,deadline_at,status) VALUES(?,?,?,?,?) ON CONFLICT(request_id) DO UPDATE SET status=EXCLUDED.status "
                +"WHERE kb_rag_tool_request.delegation_id=EXCLUDED.delegation_id AND kb_rag_tool_request.status='RUNNING'",id,grant,trace,java.sql.Timestamp.from(deadline),status);
    }
    public void cleanup() {
        jdbc.update("UPDATE kb_rag_tool_request SET status='TIMED_OUT' WHERE status='RUNNING' AND deadline_at<=clock_timestamp()");
        jdbc.update("DELETE FROM kb_rag_delegation WHERE id IN (SELECT id FROM kb_rag_delegation WHERE expires_at<clock_timestamp()-interval '1 day' LIMIT 1000)");
    }
}
