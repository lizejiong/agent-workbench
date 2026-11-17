package com.lzj.rag.backend.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.lzj.rag.backend.agent.controller.AgentContracts.Snapshot;
import com.lzj.rag.backend.agent.service.ConversationEvidence;
import com.lzj.rag.backend.knowledgebase.exception.KnowledgeBaseException;
import com.lzj.rag.backend.model.catalog.EmbeddingCatalog;
import com.lzj.rag.backend.rag.service.bo.DelegationBO;
import com.lzj.rag.backend.search.service.HybridRetrievalService;
import com.lzj.rag.backend.search.service.bo.SearchBO;
import com.lzj.rag.backend.security.model.KnowledgeBasePrincipal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

/** 每次执行许可、去重与审计使用持久短事务；连接信息只在内部受授权入口返回。 */
@Service
public class ToolLedger {
    final JdbcTemplate db;final ToolCatalog catalog;final HybridRetrievalService scopes;final ConversationEvidence evidence;
    public ToolLedger(JdbcTemplate db,ToolCatalog catalog,HybridRetrievalService scopes,ConversationEvidence evidence){
        this.db=db;this.catalog=catalog;this.scopes=scopes;this.evidence=evidence;
    }
    Map<String,Object> run(DelegationBO g,UUID id,boolean active){
        var rows=db.queryForList("SELECT r.*,c.tenant_id,c.owner_id,c.kb_ids,c.document_ids FROM kb_agent_run r JOIN kb_agent_conversation c ON c.id=r.conversation_id "
            +"WHERE r.id=? AND c.id=? AND c.tenant_id=? AND c.owner_id=? AND r.service_id=? AND c.deleted_at IS NULL",
            id,g.agentSessionId(),g.actor().tenantId(),g.actor().userId(),g.serviceId());
        if(rows.isEmpty())throw catalog.denied();var row=rows.get(0);
        if(active && (!row.get("status").equals("RUNNING") || !((Timestamp)row.get("deadline_at")).toInstant().isAfter(Instant.now())))
            throw new KnowledgeBaseException(409,"AGENT_RUN_ENDED","Run is no longer active");
        if(!catalog.encode(g.kbIds()).equals(row.get("kb_ids").toString().replace(" ",""))
            || !Objects.equals(catalog.encode(g.documentIds()),row.get("document_ids")==null?"null":row.get("document_ids").toString().replace(" ","")))throw catalog.denied();
        if(active)scopes.prepare(g.actor(),new SearchBO("?",g.kbIds(),g.documentIds(),1,null),Instant.now().plusSeconds(3));
        return row;
    }
    public List<Map<String,Object>> tools(DelegationBO g,UUID id){
        run(g,id,true);var result=new ArrayList<Map<String,Object>>(builtins());result.addAll(catalog.allowed(g.actor()));return result;
    }
    public static List<Map<String,Object>> builtins(){
        return List.of(builtin("knowledge_search","search_knowledge","知识库检索","检索当前授权知识范围，只返回结构化证据。","query",4000),
            builtin("evidence_read","evidence_read","证据读取","按本轮已获得的 evidenceId 重新鉴权读取证据，不能读取任意 chunkId。","evidenceId",80));
    }
    private static Map<String,Object> builtin(String key,String name,String title,String description,String arg,int max){
        var result=new LinkedHashMap<String,Object>();result.put("toolKey","builtin:"+key);result.put("modelName",name);result.put("displayName",title);result.put("description",description);
        result.put("inputSchema",Map.of("type","object","properties",Map.of(arg,Map.of("type","string","minLength",1,"maxLength",max)),"required",List.of(arg),"additionalProperties",false));
        result.put("kind","builtin");result.put("readOnly",true);result.put("configVersion",1);result.put("timeoutMs",10000);result.put("maxResultBytes",4096);return result;
    }
    Map<String,Object> definition(KnowledgeBasePrincipal actor,String key){
        if(key.startsWith("builtin:"))return builtins().stream().filter(d->key.equals(d.get("toolKey"))).findFirst().orElseThrow(catalog::denied);
        return catalog.approved(actor,key);
    }
    @Transactional(timeout=10)
    public Map<String,Object> start(DelegationBO g,UUID runId,ToolContracts.Start input){
        var r=run(g,runId,true);
        var safe=ToolRedactor.clean(input.arguments());
        if(catalog.encode(safe).length()>16384)throw catalog.invalid();
        String hash=EmbeddingCatalog.sha256(catalog.encode(input));
        var existing=db.queryForList("SELECT * FROM kb_tool_call WHERE call_id=? OR (run_id=? AND provider_call_id=?) FOR UPDATE",input.callId(),runId,input.providerCallId());
        if(!existing.isEmpty()){
            var old=existing.get(0);
            if(!old.get("run_id").equals(runId) || !old.get("call_id").equals(input.callId()) || !old.get("request_hash").equals(hash))throw catalog.conflict();
            if(old.get("result")==null)throw new KnowledgeBaseException(409,"TOOL_CALL_IN_PROGRESS","Call already started; execution cannot be replayed");
            return Map.of("execute",false,"result",catalog.decode(old.get("result")));
        }
        boolean allowed=true;Map<String,Object> definition=Map.of();
        try{
            definition=definition(g.actor(),input.toolKey());
            allowed=Objects.equals(((Number)definition.get("configVersion")).intValue(),input.configVersion())
                && Objects.equals(definition.get("schemaHash"),input.schemaHash());
        }catch(KnowledgeBaseException e){allowed=false;}
        var denied=deniedResult(input.callId(),"TOOL_DENIED");
        Instant deadline=((Timestamp)r.get("deadline_at")).toInstant();
        if(allowed)deadline=deadline.isBefore(Instant.now().plusMillis(((Number)definition.get("timeoutMs")).intValue()))?deadline:Instant.now().plusMillis(((Number)definition.get("timeoutMs")).intValue());
        db.update("INSERT INTO kb_tool_call(call_id,run_id,message_id,tool_key,provider_call_id,config_version,schema_hash,request_hash,batch,call_order,arguments,status,result,ended_at,deadline_at) "
            +"VALUES(?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?::jsonb,?,?)",input.callId(),runId,r.get("assistant_message_id"),input.toolKey(),input.providerCallId(),input.configVersion(),input.schemaHash(),hash,input.batch(),input.order(),catalog.encode(safe),
            allowed?"running":"denied",allowed?null:catalog.encode(denied),allowed?null:Timestamp.from(Instant.now()),Timestamp.from(deadline));
        return allowed?Map.of("execute",true):Map.of("execute",false,"result",denied);
    }
    Map<String,Object> call(DelegationBO g,UUID runId,String callId,boolean active){
        run(g,runId,active);var rows=db.queryForList("SELECT * FROM kb_tool_call WHERE call_id=? AND run_id=?",callId,runId);
        if(rows.isEmpty())throw catalog.denied();return rows.get(0);
    }
    public Map<String,Object> authorize(DelegationBO g,UUID runId,String callId){
        var c=call(g,runId,callId,true);var d=definition(g.actor(),c.get("tool_key").toString());
        if(!c.get("status").equals("running") || !((Timestamp)c.get("deadline_at")).toInstant().isAfter(Instant.now())
            || !Objects.equals(c.get("config_version"),d.get("configVersion")) || !Objects.equals(c.get("schema_hash"),d.get("schemaHash")))throw catalog.denied();
        return d;
    }
    public Map<String,Object> connection(DelegationBO g,UUID runId,String callId){
        var d=authorize(g,runId,callId);if(!d.get("kind").equals("mcp"))throw catalog.denied();
        var out=catalog.connection(catalog.server(g.actor().tenantId(),UUID.fromString(d.get("serverId").toString())));
        out.put("credentialScope",g.actor().tenantId()+":"+g.actor().userId()+":"+runId);return out;
    }
    @Transactional(timeout=10)
    public Map<String,Object> finish(DelegationBO g,UUID runId,String callId,ToolContracts.Finish result){
        var c=call(g,runId,callId,false);if(!callId.equals(result.callId()))throw catalog.invalid();
        if(!c.get("status").equals("running"))return Map.of("status",c.get("status"));
        if(result.sourceType().equals("mcp") && (!c.get("tool_key").toString().startsWith("mcp:")
            || !result.evidenceRefs().equals(List.of("M"+callId.substring(1))) || !Objects.equals(result.modelContent().get("sourceId"),"M"+callId.substring(1))))throw catalog.invalid();
        var safe=new LinkedHashMap<String,Object>();
        safe.put("callId",callId);safe.put("status",result.status());safe.put("summary",ToolRedactor.clean(result.summary()));
        safe.put("modelContent",result.sourceType().equals("knowledge")?Map.of():ToolRedactor.clean(result.modelContent()));
        safe.put("evidenceRefs",result.evidenceRefs());safe.put("sourceType",result.sourceType());safe.put("errorCode",result.errorCode());
        safe.put("truncated",result.truncated());safe.put("durationMs",Math.min(120000,Math.max(0,Duration.between(((Timestamp)c.get("started_at")).toInstant(),Instant.now()).toMillis())));
        if(catalog.encode(safe).getBytes(java.nio.charset.StandardCharsets.UTF_8).length>20000)throw catalog.invalid();
        db.update("UPDATE kb_tool_call SET status=?,result=?::jsonb,error_code=?,ended_at=clock_timestamp() WHERE call_id=? AND status='running'",result.status(),catalog.encode(safe),result.errorCode(),callId);
        if(c.get("tool_key").toString().startsWith("mcp:") && Set.of("success","empty").contains(result.status()))
            db.update("INSERT INTO kb_tool_dependency(run_id,source_call_id) VALUES(?,?) ON CONFLICT DO NOTHING",runId,callId);
        return Map.of("status",result.status());
    }
    public Map<String,Object> readEvidence(DelegationBO g,UUID runId,String evidenceId){
        var r=run(g,runId,true);
        String raw=db.queryForObject("SELECT evidence::text FROM kb_agent_message WHERE id=?",String.class,r.get("assistant_message_id"));
        List<Snapshot> saved;
        try{saved=catalog.json.readValue(raw,new TypeReference<>(){});}catch(Exception e){throw catalog.invalid();}
        var available=evidence.visible(g.actor(),g.kbIds(),g.documentIds(),saved);
        if(saved.size()!=available.size())throw new KnowledgeBaseException(409,"EVIDENCE_CHANGED","Evidence is no longer accessible");
        return available.stream().filter(e->evidenceId.equals(e.get("evidenceId"))).findFirst().orElseThrow(catalog::denied);
    }
    public void validate(DelegationBO g,UUID runId,List<String> sourceIds){
        run(g,runId,true);
        for(var source:sourceIds){
            var rows=db.queryForList("SELECT * FROM kb_tool_call WHERE run_id=? AND call_id=? AND status='success'",runId,"T"+source.substring(1));
            if(rows.isEmpty() || !current(g.actor(),rows.get(0)))throw catalog.denied();
        }
    }
    boolean current(KnowledgeBasePrincipal actor,Map<String,Object> c){
        try{var d=definition(actor,c.get("tool_key").toString());return Objects.equals(d.get("configVersion"),c.get("config_version")) && Objects.equals(d.get("schemaHash"),c.get("schema_hash"));}
        catch(KnowledgeBaseException e){return false;}
    }
    public boolean historyAllowed(KnowledgeBasePrincipal actor,UUID runId){
        return db.queryForList("SELECT c.* FROM kb_tool_dependency d JOIN kb_tool_call c ON c.call_id=d.source_call_id WHERE d.run_id=?",runId).stream().allMatch(c->current(actor,c));
    }
    public List<Map<String,Object>> historyCalls(KnowledgeBasePrincipal actor,UUID runId){
        if(!historyAllowed(actor,runId))return List.of();
        return db.queryForList("SELECT * FROM kb_tool_call WHERE run_id=? ORDER BY batch,call_order",runId);
    }
    public String displayName(KnowledgeBasePrincipal actor,String key){
        try{return definition(actor,key).get("displayName").toString();}catch(KnowledgeBaseException e){return "只读工具";}
    }
    @Transactional(timeout=10)
    public void inherit(DelegationBO grant,UUID runId,List<UUID> messageIds){
        var r=run(grant,runId,true);
        long upper=db.queryForObject("SELECT sequence FROM kb_agent_message WHERE id=?",Long.class,r.get("user_message_id"));
        for(var messageId:messageIds){
            var rows=db.queryForList("SELECT role,run_id FROM kb_agent_message WHERE id=? AND conversation_id=? AND sequence<?",messageId,grant.agentSessionId(),upper);
            if(rows.isEmpty())throw catalog.denied();var source=rows.get(0);
            if(!source.get("role").equals("assistant"))continue;
            if(!historyAllowed(grant.actor(),(UUID)source.get("run_id")))throw new KnowledgeBaseException(409,"EVIDENCE_CHANGED","Historical tool sources changed");
            db.update("INSERT INTO kb_tool_dependency(run_id,source_call_id) SELECT ?,source_call_id FROM kb_tool_dependency WHERE run_id=? ON CONFLICT DO NOTHING",runId,source.get("run_id"));
        }
    }
    public List<Map<String,Object>> records(KnowledgeBasePrincipal actor,UUID runId,int limit){
        var rows=db.queryForList("SELECT c.* FROM kb_tool_call c JOIN kb_agent_conversation v ON v.id=(SELECT conversation_id FROM kb_agent_run WHERE id=c.run_id) "
            +"WHERE v.tenant_id=? AND (?::uuid IS NULL OR c.run_id=?) ORDER BY c.started_at DESC LIMIT ?",actor.tenantId(),runId,runId,limit);
        return rows.stream().map(c->{
            var safe=new LinkedHashMap<String,Object>();for(String k:List.of("call_id","run_id","tool_key","provider_call_id","status","error_code","batch","call_order","started_at","ended_at"))safe.put(k,c.get(k));
            // 管理审计也按当前知识 ACL 隐藏内容，只保留状态/关联标识。
            boolean owner=Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM kb_agent_conversation v JOIN kb_agent_run r ON r.conversation_id=v.id WHERE r.id=? AND v.tenant_id=? AND v.owner_id=? AND v.deleted_at IS NULL)",Boolean.class,c.get("run_id"),actor.tenantId(),actor.userId()));
            if(owner && contentAllowed(actor,(UUID)c.get("run_id")) && current(actor,c)){
                safe.put("arguments",catalog.decode(c.get("arguments")));if(c.get("result")!=null)safe.put("result",catalog.decode(c.get("result")));
            }return (Map<String,Object>)safe;
        }).toList();
    }
    private boolean contentAllowed(KnowledgeBasePrincipal actor,UUID runId){
        if(!historyAllowed(actor,runId))return false;
        var rows=db.queryForList("SELECT m.evidence,v.kb_ids,v.document_ids FROM kb_agent_message m JOIN kb_agent_conversation v ON v.id=m.conversation_id WHERE m.run_id=? AND m.role='assistant' AND v.tenant_id=? AND v.owner_id=? AND v.deleted_at IS NULL",runId,actor.tenantId(),actor.userId());
        if(rows.isEmpty())return false;var row=rows.get(0);
        try {
            List<Snapshot> snapshots=catalog.json.readValue(row.get("evidence").toString(),new TypeReference<>(){});
            List<UUID> kbs=catalog.json.readValue(row.get("kb_ids").toString(),new TypeReference<>(){});
            List<UUID> docs=row.get("document_ids")==null?null:catalog.json.readValue(row.get("document_ids").toString(),new TypeReference<>(){});
            return evidence.visible(actor,kbs,docs,snapshots).size()==snapshots.size();
        }catch(Exception e){return false;}
    }
    @Scheduled(fixedDelay=5000)
    @Transactional(timeout=10)
    public void expire(){
        db.update("UPDATE kb_tool_call c SET status=CASE WHEN r.status='CANCELLED' THEN 'cancelled' ELSE 'timeout' END,error_code=CASE WHEN r.status='CANCELLED' THEN 'TOOL_CANCELLED' ELSE 'TOOL_TIMEOUT' END,ended_at=clock_timestamp() "
            +"FROM kb_agent_run r WHERE c.run_id=r.id AND c.status='running' AND (c.deadline_at<=clock_timestamp() OR r.status<>'RUNNING')");
    }
    private Map<String,Object> deniedResult(String id,String code){return Map.of("callId",id,"status","denied","modelContent",Map.of(),"summary","工具未启用或未获授权","evidenceRefs",List.of(),"sourceType","none","errorCode",code,"truncated",false,"durationMs",0);}
}
