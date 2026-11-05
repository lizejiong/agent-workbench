package com.lzj.rag.backend.agent.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lzj.rag.backend.agent.controller.AgentContracts.*;
import com.lzj.rag.backend.agent.service.ConversationEvidence;
import com.lzj.rag.backend.knowledgebase.exception.KnowledgeBaseException;
import com.lzj.rag.backend.model.catalog.EmbeddingCatalog;
import com.lzj.rag.backend.rag.service.bo.DelegationBO;
import com.lzj.rag.backend.search.service.HybridRetrievalService;
import com.lzj.rag.backend.search.service.bo.SearchBO;
import com.lzj.rag.backend.security.model.KnowledgeBasePrincipal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Java 是 Agent 业务会话的唯一账本。每个请求只取得一次执行权，终态不恢复执行。 */
@Repository
public class ConversationLedger {
    private final JdbcTemplate jdbc;private final ObjectMapper json;
    private final HybridRetrievalService scopes;private final ConversationEvidence evidence;
    public ConversationLedger(JdbcTemplate jdbc,ObjectMapper json,HybridRetrievalService scopes,ConversationEvidence evidence) {
        this.jdbc=jdbc;this.json=json;this.scopes=scopes;this.evidence=evidence;
    }
    @Transactional(timeout=10)
    public Conversation create(KnowledgeBasePrincipal actor,Create input) {
        String title=input.title()==null || input.title().isBlank()?"新对话":clean(input.title());
        String hash=hash(Arrays.asList(title,input.kbIds(),input.documentIds()));
        var old=jdbc.queryForList("SELECT id,create_hash,deleted_at FROM kb_agent_conversation WHERE tenant_id=? AND owner_id=? AND create_request_id=?",
                actor.tenantId(),actor.userId(),input.requestId());
        if(!old.isEmpty()) {
            if(!hash.equals(old.get(0).get("create_hash")) || old.get(0).get("deleted_at")!=null)throw conflict("CONVERSATION_REQUEST_CONFLICT");
            return require(actor,(UUID)old.get(0).get("id"),false);
        }
        var scope=scopes.prepare(actor,new SearchBO("?",input.kbIds(),input.documentIds(),1,null),Instant.now().plusSeconds(5));
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO kb_agent_conversation(id,tenant_id,owner_id,create_request_id,create_hash,title,kb_ids,document_ids) VALUES(?,?,?,?,?,?,?::jsonb,?::jsonb) ON CONFLICT(tenant_id,owner_id,create_request_id) DO NOTHING",
                id,actor.tenantId(),actor.userId(),input.requestId(),hash,title,encode(scope.kbIds()),encode(scope.documentIds()));
        var persisted=jdbc.queryForMap("SELECT id,create_hash,deleted_at FROM kb_agent_conversation WHERE tenant_id=? AND owner_id=? AND create_request_id=?",actor.tenantId(),actor.userId(),input.requestId());
        if(!hash.equals(persisted.get("create_hash")) || persisted.get("deleted_at")!=null)throw conflict("CONVERSATION_REQUEST_CONFLICT");
        return require(actor,(UUID)persisted.get("id"),false);
    }
    public Map<String,Object> list(KnowledgeBasePrincipal actor,int page,int size,String search) {
        String filter=search==null?"":search.strip().toLowerCase(Locale.ROOT);
        Object[] args={actor.tenantId(),actor.userId(),filter};
        Long total=jdbc.queryForObject("SELECT count(*) FROM kb_agent_conversation WHERE tenant_id=? AND owner_id=? AND deleted_at IS NULL AND position(? in lower(title))>0",Long.class,args);
        var records=jdbc.query("SELECT c.*,(SELECT status FROM kb_agent_run r WHERE r.conversation_id=c.id ORDER BY created_at DESC,id DESC LIMIT 1) AS run_status FROM kb_agent_conversation c "
                +"WHERE tenant_id=? AND owner_id=? AND deleted_at IS NULL AND position(? in lower(title))>0 ORDER BY active_at DESC,id DESC LIMIT ? OFFSET ?",
                this::conversation,actor.tenantId(),actor.userId(),filter,size,(long)(page-1)*size);
        return Map.of("current",page,"size",size,"total",total,"records",records);
    }
    public Conversation require(KnowledgeBasePrincipal actor,UUID id,boolean lock) {
        var rows=jdbc.query("SELECT c.*,(SELECT status FROM kb_agent_run r WHERE r.conversation_id=c.id ORDER BY created_at DESC,id DESC LIMIT 1) AS run_status FROM kb_agent_conversation c WHERE id=? AND tenant_id=? AND owner_id=? AND deleted_at IS NULL"+(lock?" FOR UPDATE OF c":""),
                this::conversation,id,actor.tenantId(),actor.userId());
        if(rows.isEmpty())throw missing();return rows.get(0);
    }
    public Conversation delegated(DelegationBO g,UUID id,boolean lock) {
        if(!id.equals(g.agentSessionId()))throw missing();
        var c=require(g.actor(),id,lock);
        if(!c.kbIds().equals(g.kbIds()) || !Objects.equals(c.documentIds(),g.documentIds()))throw conflict("CONVERSATION_SCOPE_CHANGED");
        return c;
    }
    @Transactional(timeout=10)
    public Conversation rename(KnowledgeBasePrincipal actor,UUID id,String title) {
        require(actor,id,true);jdbc.update("UPDATE kb_agent_conversation SET title=? WHERE id=?",clean(title),id);return require(actor,id,false);
    }
    @Transactional(timeout=10)
    public void delete(KnowledgeBasePrincipal actor,UUID id) {
        require(actor,id,true);
        jdbc.update("UPDATE kb_agent_run SET status='CANCELLED',error_code='CONVERSATION_DELETED',ended_at=clock_timestamp(),updated_at=clock_timestamp() WHERE conversation_id=? AND status='RUNNING'",id);
        jdbc.update("DELETE FROM kb_agent_message WHERE conversation_id=?",id);
        jdbc.update("UPDATE kb_agent_conversation SET deleted_at=clock_timestamp(),title='已删除',kb_ids='[]',document_ids=NULL WHERE id=?",id);
        // 保留最小请求墓碑阻止重复执行；正文、工具和证据随消息物理删除。
    }
    @Transactional(timeout=10)
    public Run begin(DelegationBO g,UUID conversationId,Begin input,String traceId) {
        delegated(g,conversationId,true);expire(conversationId);
        String question=clean(input.question());String requestHash=hash(List.of(input.messageId(),question));
        var old=jdbc.query("SELECT * FROM kb_agent_run WHERE conversation_id=? AND request_id=?",this::run,conversationId,input.requestId());
        if(!old.isEmpty()) {
            var saved=jdbc.queryForMap("SELECT request_hash FROM kb_agent_run WHERE id=?",old.get(0).runId());
            if(!requestHash.equals(saved.get("request_hash")))throw conflict("AGENT_REQUEST_CONFLICT");
            return old.get(0);
        }
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM kb_agent_run WHERE conversation_id=? AND status='RUNNING')",Boolean.class,conversationId)))throw conflict("SESSION_BUSY");
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM kb_agent_run WHERE user_message_id=? OR assistant_message_id=?)",Boolean.class,input.messageId(),input.messageId())))throw conflict("AGENT_MESSAGE_CONFLICT");
        Instant deadline=input.deadline();Instant cap=Instant.now().plusSeconds(120);
        if(g.expiresAt().isBefore(cap))cap=g.expiresAt();if(deadline.isAfter(cap))deadline=cap;
        if(!deadline.isAfter(Instant.now()))throw new KnowledgeBaseException(504,"AGENT_TIMEOUT","Execution deadline expired");
        UUID id=UUID.randomUUID(),assistant=UUID.randomUUID();
        long sequence=jdbc.queryForObject("SELECT next_sequence FROM kb_agent_conversation WHERE id=?",Long.class,conversationId);
        jdbc.update("INSERT INTO kb_agent_run(id,conversation_id,request_id,request_hash,user_message_id,assistant_message_id,service_id,trace_id,status,deadline_at) VALUES(?,?,?,?,?,?,?,?,'RUNNING',?)",
                id,conversationId,input.requestId(),requestHash,input.messageId(),assistant,g.serviceId(),traceId,Timestamp.from(deadline));
        jdbc.update("INSERT INTO kb_agent_message(id,conversation_id,run_id,request_id,sequence,role,content,status) VALUES(?,?,?,?,?,'user',?,'COMPLETED'),(?,?,?,?,?,'assistant','','RUNNING')",
                input.messageId(),conversationId,id,input.requestId(),sequence,question,assistant,conversationId,id,input.requestId(),sequence+1);
        jdbc.update("UPDATE kb_agent_conversation SET next_sequence=next_sequence+2,active_at=clock_timestamp(),title=CASE WHEN next_sequence=1 AND title='新对话' THEN ? ELSE title END WHERE id=?",
                question.codePoints().limit(28).collect(StringBuilder::new,StringBuilder::appendCodePoint,StringBuilder::append).toString(),conversationId);
        Run r=get(g,conversationId,id);
        return new Run(r.runId(),r.conversationId(),r.requestId(),r.userMessageId(),r.assistantMessageId(),r.traceId(),r.status(),r.checkpointSequence(),r.deadline(),r.errorCode(),true);
    }
    public Run get(DelegationBO g,UUID conversationId,UUID id) {
        delegated(g,conversationId,false);return ownedRun(g,conversationId,id,false);
    }
    @Transactional(timeout=10)
    public Map<String,Object> result(DelegationBO g,UUID conversationId,UUID id) {
        delegated(g,conversationId,false);return result(g.actor(),conversationId,id);
    }
    @Transactional(timeout=10)
    public Map<String,Object> result(KnowledgeBasePrincipal actor,UUID conversationId,UUID id) {
        var c=require(actor,conversationId,false);expire(conversationId);
        var runs=jdbc.query("SELECT * FROM kb_agent_run WHERE id=? AND conversation_id=?",this::run,id,conversationId);
        if(runs.isEmpty())throw missing();
        var row=jdbc.queryForMap("SELECT * FROM kb_agent_message WHERE id=?",runs.get(0).assistantMessageId());
        return Map.of("run",runs.get(0),"message",view(actor,c,row));
    }
    private Run ownedRun(DelegationBO g,UUID conversationId,UUID id,boolean lock) {
        var rows=jdbc.query("SELECT * FROM kb_agent_run WHERE id=? AND conversation_id=? AND service_id=?"+(lock?" FOR UPDATE":""),this::run,id,conversationId,g.serviceId());
        if(rows.isEmpty())throw missing();return rows.get(0);
    }
    @Transactional(timeout=10)
    public Run checkpoint(DelegationBO g,UUID conversationId,UUID id,Checkpoint input) {
        var c=delegated(g,conversationId,true);expire(conversationId);var r=ownedRun(g,conversationId,id,true);
        String hash=hash(input);
        if(input.sequence()<=r.checkpointSequence()) {
            String old=jdbc.queryForObject("SELECT checkpoint_hash FROM kb_agent_run WHERE id=?",String.class,id);
            if(input.sequence()==r.checkpointSequence() && hash.equals(old))return r;
            throw conflict("AGENT_CHECKPOINT_CONFLICT");
        }
        if(Set.of("COMPLETED","FAILED").contains(r.status()))return r;
        if(encode(input).length()>524288)throw new KnowledgeBaseException(413,"AGENT_SNAPSHOT_BUDGET","Snapshot too large");
        var ids=input.evidence().stream().map(Snapshot::evidenceId).toList();
        if(new HashSet<>(ids).size()!=ids.size() || !ids.containsAll(input.citations()))throw conflict("CITATION_INVALID");
        if(input.status().equals("COMPLETED")) {
            var matched=new HashSet<String>();var pattern=java.util.regex.Pattern.compile("\\[(K[a-f0-9]{32}_E[1-9][0-9]{0,3})\\]");
            var matcher=pattern.matcher(input.content());while(matcher.find())matched.add(matcher.group(1));
            if(!matched.equals(new HashSet<>(input.citations())) || pattern.matcher(input.content()).replaceAll("").contains("[K") || input.content().matches("(?s).*\\[E[0-9]+\\].*"))throw conflict("CITATION_INVALID");
        }
        String previousText=jdbc.queryForObject("SELECT content FROM kb_agent_message WHERE id=?",String.class,r.assistantMessageId());
        if(!input.content().startsWith(previousText))throw conflict("AGENT_SNAPSHOT_CONFLICT");
        List<Snapshot> saved=decode(jdbc.queryForObject("SELECT evidence::text FROM kb_agent_message WHERE id=?",String.class,r.assistantMessageId()),new TypeReference<>(){});
        if(!input.evidence().containsAll(saved))throw conflict("AGENT_SNAPSHOT_CONFLICT");
        boolean current=evidence.visible(g.actor(),c.kbIds(),c.documentIds(),input.evidence()).size()==input.evidence().size();
        if(!current && (!Set.of("CANCELLED","FAILED","TIMED_OUT").contains(input.status()) || !saved.containsAll(input.evidence())))throw conflict("EVIDENCE_CHANGED");
        String state=r.status().equals("RUNNING")?input.status():r.status();
        if(!r.status().equals("RUNNING") && Duration.between(jdbc.queryForObject("SELECT ended_at FROM kb_agent_run WHERE id=?",Timestamp.class,id).toInstant(),Instant.now()).toSeconds()>5)return r;
        jdbc.update("UPDATE kb_agent_message SET content=?,status=?,evidence=?::jsonb,citations=?::jsonb,tools=?::jsonb,model=?,prompt_version=?,prompt_hash=?,updated_at=clock_timestamp() WHERE id=?",
                input.content(),state,encode(input.evidence()),encode(input.citations()),encode(input.tools()),input.model(),input.promptVersion(),input.promptHash(),r.assistantMessageId());
        jdbc.update("UPDATE kb_agent_run SET status=?,checkpoint_sequence=?,checkpoint_hash=?,error_code=?,updated_at=clock_timestamp(),ended_at=CASE WHEN ?='RUNNING' THEN ended_at ELSE COALESCE(ended_at,clock_timestamp()) END WHERE id=?",
                state,input.sequence(),hash,state.equals("COMPLETED")?null:input.errorCode()==null?r.errorCode():input.errorCode(),state,id);
        jdbc.update("UPDATE kb_agent_conversation SET active_at=clock_timestamp() WHERE id=?",conversationId);
        return ownedRun(g,conversationId,id,false);
    }
    @Transactional(timeout=10)
    public Run cancel(KnowledgeBasePrincipal actor,UUID conversationId,UUID id) {
        require(actor,conversationId,true);expire(conversationId);
        int count=jdbc.update("UPDATE kb_agent_run SET status='CANCELLED',error_code='USER_CANCELLED',ended_at=clock_timestamp(),updated_at=clock_timestamp() WHERE id=? AND conversation_id=? AND status='RUNNING'",id,conversationId);
        if(count>0)jdbc.update("UPDATE kb_agent_message SET status='CANCELLED',updated_at=clock_timestamp() WHERE run_id=? AND role='assistant'",id);
        var rows=jdbc.query("SELECT * FROM kb_agent_run WHERE id=? AND conversation_id=?",this::run,id,conversationId);
        if(rows.isEmpty())throw missing();return rows.get(0);
    }
    @Transactional(timeout=10)
    public Map<String,Object> messages(KnowledgeBasePrincipal actor,UUID id,long before,int size) {
        var c=require(actor,id,false);expire(id);
        var rows=jdbc.queryForList("SELECT * FROM kb_agent_message WHERE conversation_id=? AND sequence<? ORDER BY sequence DESC LIMIT ?",id,before,size+1);
        boolean more=rows.size()>size;if(more)rows=new ArrayList<>(rows.subList(0,size));Collections.reverse(rows);
        var items=new ArrayList<Map<String,Object>>();for(var row:rows)items.add(view(actor,c,row));
        return Map.of("records",items,"hasMore",more,"nextBeforeSequence",rows.isEmpty()?0L:((Number)rows.get(0).get("sequence")).longValue());
    }
    public List<Map<String,Object>> history(DelegationBO g,UUID id) {
        delegated(g,id,false);
        @SuppressWarnings("unchecked") var rows=(List<Map<String,Object>>)messages(g.actor(),id,Long.MAX_VALUE,60).get("records");
        return rows.stream().filter(r->r.get("status").equals("COMPLETED") && !Boolean.TRUE.equals(r.get("sourcesRestricted")))
                .map(r->Map.<String,Object>of("id",r.get("id"),"role",r.get("role"),"content",r.get("content"),"runId",r.get("runId"),"evidence",r.get("evidence"))).toList();
    }
    private Map<String,Object> view(KnowledgeBasePrincipal actor,Conversation c,Map<String,Object> row) {
        List<Snapshot> snapshots=decode(row.get("evidence").toString(),new TypeReference<>(){});
        var available=evidence.visible(actor,c.kbIds(),c.documentIds(),snapshots);
        boolean restricted=available.size()!=snapshots.size();
        List<String> selected=decode(row.get("citations").toString(),new TypeReference<>(){});
        var value=new LinkedHashMap<String,Object>();
        for(String key:List.of("id","role","content","sequence","status","model"))value.put(key,row.get(key));
        value.put("runId",row.get("run_id"));value.put("requestId",row.get("request_id"));
        value.put("createdAt",((Timestamp)row.get("created_at")).toInstant());value.put("updatedAt",((Timestamp)row.get("updated_at")).toInstant());
        value.put("promptVersion",row.get("prompt_version"));value.put("promptHash",row.get("prompt_hash"));
        value.put("sourcesRestricted",restricted);value.put("evidence",restricted?List.of():available);
        value.put("citations",restricted?List.of():available.stream().filter(e->selected.contains(e.get("evidenceId"))).toList());
        List<Tool> tools=decode(row.get("tools").toString(),new TypeReference<>(){});
        value.put("tools",tools.stream().map(t->!row.get("status").equals("RUNNING") && t.status().equals("RUNNING")
                ?new Tool(t.toolCallId(),t.name(),"UNAVAILABLE",t.requestId(),t.traceId(),t.degraded(),t.strategy(),t.toolTruncated(),"AGENT_RUN_ENDED",false):t).toList());
        if(restricted)value.put("content","相关知识已变化或不可访问，本条回答已隐藏。请重新提问。");
        return value;
    }
    private Conversation conversation(ResultSet r,int n)throws SQLException {
        return new Conversation(r.getObject("id",UUID.class),r.getString("title"),decode(r.getString("kb_ids"),new TypeReference<>(){}),
                decode(r.getString("document_ids"),new TypeReference<>(){}),r.getTimestamp("created_at").toInstant(),r.getTimestamp("active_at").toInstant(),Objects.toString(r.getString("run_status"),"IDLE"));
    }
    private Run run(ResultSet r,int n)throws SQLException {
        String state=r.getString("status");if(state.equals("RUNNING") && !r.getTimestamp("deadline_at").toInstant().isAfter(Instant.now()))state="TIMED_OUT";
        return new Run(r.getObject("id",UUID.class),r.getObject("conversation_id",UUID.class),r.getObject("request_id",UUID.class),r.getObject("user_message_id",UUID.class),
                r.getObject("assistant_message_id",UUID.class),r.getString("trace_id"),state,r.getLong("checkpoint_sequence"),r.getTimestamp("deadline_at").toInstant(),r.getString("error_code"),false);
    }
    private void expire(UUID id) {
        jdbc.update("UPDATE kb_agent_run SET status='TIMED_OUT',error_code='AGENT_TIMEOUT',ended_at=clock_timestamp(),updated_at=clock_timestamp() WHERE conversation_id=? AND status='RUNNING' AND deadline_at<=clock_timestamp()",id);
        jdbc.update("UPDATE kb_agent_message m SET status=r.status,updated_at=clock_timestamp() FROM kb_agent_run r WHERE m.run_id=r.id AND m.conversation_id=? AND m.role='assistant' AND m.status='RUNNING' AND r.status<>'RUNNING'",id);
    }
    @Transactional(timeout=10)
    public void expireBatch() {
        var ids=jdbc.queryForList("SELECT DISTINCT conversation_id FROM kb_agent_run WHERE status='RUNNING' AND deadline_at<=clock_timestamp() LIMIT 100",UUID.class);
        ids.forEach(this::expire);
    }
    private String clean(String v){if(v.indexOf(0)>=0 || v.isBlank())throw new KnowledgeBaseException(400,"KB_INVALID_INPUT","Invalid text");return v.strip();}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("Cannot encode conversation",e);}}
    private <T> T decode(String value,TypeReference<T> type){if(value==null || value.equals("null"))return null;try{return json.readValue(value,type);}catch(Exception e){throw new IllegalStateException("Invalid conversation data",e);}}
    private String hash(Object v){return EmbeddingCatalog.sha256(encode(v));}
    private KnowledgeBaseException missing(){return new KnowledgeBaseException(404,"CONVERSATION_NOT_FOUND","Conversation or run not found");}
    private KnowledgeBaseException conflict(String code){return new KnowledgeBaseException(409,code,"Conversation state or request conflicts");}
}
