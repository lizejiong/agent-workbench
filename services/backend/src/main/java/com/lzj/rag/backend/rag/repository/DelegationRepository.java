package com.lzj.rag.backend.rag.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lzj.rag.backend.knowledgebase.exception.KnowledgeBaseException;
import com.lzj.rag.backend.security.model.KnowledgeBasePrincipal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.*;

@Repository
public class DelegationRepository {
    public record Grant(UUID id,String serviceId,String username,String tenantId,String ownerId,UUID sessionId,
                        List<UUID> kbIds,List<UUID> documentIds,Instant expiresAt) {}
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public DelegationRepository(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}
    public void insert(Grant g,String hash) {
        jdbc.update("INSERT INTO kb_rag_delegation(id,token_hash,service_id,username,tenant_id,owner_id,agent_session_id,kb_ids,document_ids,expires_at) VALUES(?,?,?,?,?,?,?,?::jsonb,?::jsonb,?)",
                g.id(),hash,g.serviceId(),g.username(),g.tenantId(),g.ownerId(),g.sessionId(),encode(g.kbIds()),g.documentIds()==null?null:encode(g.documentIds()),java.sql.Timestamp.from(g.expiresAt()));
    }
    public Grant find(String service,String hash) {
        var rows=jdbc.query("SELECT * FROM kb_rag_delegation WHERE service_id=? AND token_hash=? AND revoked_at IS NULL AND expires_at>clock_timestamp()",
                (rs,n)->new Grant(rs.getObject("id",UUID.class),rs.getString("service_id"),rs.getString("username"),rs.getString("tenant_id"),rs.getString("owner_id"),
                        rs.getObject("agent_session_id",UUID.class),ids(rs.getString("kb_ids")),ids(rs.getString("document_ids")),rs.getTimestamp("expires_at").toInstant()),service,hash);
        if(rows.isEmpty())throw invalid();return rows.get(0);
    }
    public boolean active(UUID id){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM kb_rag_delegation WHERE id=? AND revoked_at IS NULL AND expires_at>clock_timestamp())",Boolean.class,id));}
    public void revoke(KnowledgeBasePrincipal actor,UUID id) {
        int count=jdbc.update("UPDATE kb_rag_delegation SET revoked_at=COALESCE(revoked_at,clock_timestamp()) WHERE id=? AND tenant_id=? AND owner_id=?",id,actor.tenantId(),actor.userId());
        if(count!=1)throw new KnowledgeBaseException(404,"RAG_DELEGATION_NOT_FOUND","Delegation not found");
    }
    private List<UUID> ids(String value) {if(value==null)return null;try{return List.copyOf(json.readValue(value,new TypeReference<List<UUID>>(){}));}catch(Exception e){throw new IllegalStateException("Invalid delegation scope",e);}}
    private String encode(Object v){try{return json.writeValueAsString(v);}catch(Exception e){throw new IllegalStateException("Cannot encode delegation",e);}}
    public static KnowledgeBaseException invalid(){return new KnowledgeBaseException(401,"RAG_DELEGATION_INVALID","User delegation is missing, expired or unavailable");}
}
