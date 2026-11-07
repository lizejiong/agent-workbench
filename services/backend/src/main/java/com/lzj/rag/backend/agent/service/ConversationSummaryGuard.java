package com.lzj.rag.backend.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lzj.rag.backend.agent.controller.AgentContracts.Conversation;
import com.lzj.rag.backend.agent.controller.SummaryContracts.*;
import com.lzj.rag.backend.model.catalog.EmbeddingCatalog;
import com.lzj.rag.backend.security.model.KnowledgeBasePrincipal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.*;

/** 读取摘要及派生答案前重验不可变来源和当前有效证据，不用 TTL 代替授权。 */
@Component
public class ConversationSummaryGuard {
    // PostgreSQL 内置 sha256，无额外扩展；只回传指纹，避免每次复核搬运全部历史正文/快照。
    private static final String SOURCE_HASH="encode(sha256(convert_to(jsonb_build_array(id,run_id,sequence,role,status,content,evidence,context_summary_revision)::text,'UTF8')),'hex')";
    private final JdbcTemplate jdbc;private final ObjectMapper json;private final ConversationEvidence evidence;
    public ConversationSummaryGuard(JdbcTemplate jdbc,ObjectMapper json,ConversationEvidence evidence){this.jdbc=jdbc;this.json=json;this.evidence=evidence;}
    public Summary read(KnowledgeBasePrincipal actor,Conversation c,long revision) {
        var rows=jdbc.queryForList("SELECT * FROM kb_agent_context_summary WHERE conversation_id=? AND revision=?",c.id(),revision);
        if(rows.isEmpty())return null;
        var row=rows.get(0);
        List<Source> sources=decode(row.get("sources"),new TypeReference<>(){});
        if(sources.isEmpty() || sources.size()>512)return null;
        String ids=sources.stream().map(s->s.messageId().toString()).collect(java.util.stream.Collectors.joining(",","{","}"));
        var current=jdbc.queryForList("SELECT id,"+SOURCE_HASH+" AS source_hash FROM kb_agent_message WHERE conversation_id=? AND id=ANY(?::uuid[])",c.id(),ids);
        var hashes=new HashMap<UUID,String>();for(var source:current)hashes.put((UUID)source.get("id"),source.get("source_hash").toString());
        if(sources.stream().anyMatch(s->!s.hash().equals(hashes.get(s.messageId()))))return null;
        List<com.lzj.rag.backend.agent.controller.AgentContracts.Snapshot> snapshots=decode(row.get("evidence"),new TypeReference<>(){});
        if(evidence.visible(actor,c.kbIds(),c.documentIds(),snapshots).size()!=snapshots.size())return null;
        return new Summary(revision,((Number)row.get("through_sequence")).longValue(),row.get("source_hash").toString(),sources,
                decode(row.get("content"),new TypeReference<>(){}),snapshots,row.get("model").toString(),row.get("prompt_version").toString(),row.get("prompt_hash").toString(),row.get("policy_version").toString());
    }
    public Source source(Map<String,Object> row){return new Source((UUID)row.get("id"),jdbc.queryForObject("SELECT "+SOURCE_HASH+" FROM kb_agent_message WHERE id=?",String.class,row.get("id")));}
    public String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("Cannot encode context summary",e);}}
    public String hash(Object value){return EmbeddingCatalog.sha256(encode(value));}
    public <T> T decode(Object value,TypeReference<T> type){try{return json.readValue(value.toString(),type);}catch(Exception e){throw new IllegalStateException("Invalid context summary",e);}}
}
