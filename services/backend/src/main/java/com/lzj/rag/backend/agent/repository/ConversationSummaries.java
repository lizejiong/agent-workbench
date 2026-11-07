package com.lzj.rag.backend.agent.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.lzj.rag.backend.agent.controller.AgentContracts.*;
import com.lzj.rag.backend.agent.controller.SummaryContracts.*;
import com.lzj.rag.backend.agent.service.ConversationSummaryGuard;
import com.lzj.rag.backend.knowledgebase.exception.KnowledgeBaseException;
import com.lzj.rag.backend.rag.service.bo.DelegationBO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 一轮至多推进八个旧轮次；积压未追齐时只返回近期历史，不伪造连续摘要。 */
@Repository
public class ConversationSummaries {
    private final JdbcTemplate jdbc;private final ConversationLedger ledger;private final ConversationSummaryGuard guard;
    public ConversationSummaries(JdbcTemplate jdbc,ConversationLedger ledger,ConversationSummaryGuard guard){this.jdbc=jdbc;this.ledger=ledger;this.guard=guard;}
    private record Prepared(long revision,Summary base,List<Map<String,Object>> turns,List<Source> sources,
            List<Snapshot> evidence,long through,String hash,boolean hasMore,boolean caughtUp) {}
    @Transactional(readOnly=true,timeout=10)
    public Map<String,Object> prepare(DelegationBO g,UUID id,UUID runId,int retained,String policy) {
        var p=prepareInternal(g,id,runId,retained,policy);
        var result=new LinkedHashMap<String,Object>();
        result.put("expectedRevision",p.revision());result.put("summary",p.base());result.put("turns",p.turns());
        result.put("sourceHash",p.hash());result.put("throughSequence",p.through());result.put("hasMore",p.hasMore());result.put("caughtUp",p.caughtUp());
        return result;
    }
    private Prepared prepareInternal(DelegationBO g,UUID id,UUID runId,int retained,String policy) {
        var c=ledger.delegated(g,id,false);var run=ledger.get(g,id,runId);
        if(!run.status().equals("RUNNING"))throw conflict("AGENT_RUN_ENDED");
        long upper=jdbc.queryForObject("SELECT sequence FROM kb_agent_message WHERE id=?",Long.class,run.userMessageId());
        var recent=jdbc.queryForList("SELECT sequence FROM kb_agent_message WHERE conversation_id=? AND role='user' AND sequence<? ORDER BY sequence DESC LIMIT ?",Long.class,id,upper,retained);
        long cutoff=recent.isEmpty()?upper:recent.get(recent.size()-1);
        Long latest=jdbc.queryForObject("SELECT COALESCE(max(revision),0) FROM kb_agent_context_summary WHERE conversation_id=?",Long.class,id);
        Summary base=latest==0?null:guard.read(g.actor(),c,latest);
        // 运行上界和保留策略变化不能把较新的摘要带进更早的模型输入。
        if(base!=null && (base.throughSequence()>=cutoff || !base.policyVersion().equals(policy)))base=null;
        long after=base==null?0:base.throughSequence();
        var users=jdbc.queryForList("SELECT m.*,r.status AS execution_status FROM kb_agent_message m JOIN kb_agent_run r ON r.id=m.run_id WHERE m.conversation_id=? AND m.role='user' AND m.sequence>? AND m.sequence<? ORDER BY m.sequence LIMIT 9",id,after,cutoff);
        boolean more=users.size()>8;if(more)users=new ArrayList<>(users.subList(0,8));
        var turns=new ArrayList<Map<String,Object>>();var sourceMap=new LinkedHashMap<UUID,Source>();var deps=new LinkedHashMap<String,Snapshot>();
        if(base!=null){base.sources().forEach(s->addSource(sourceMap,s));base.evidence().forEach(s->deps.put(s.evidenceId(),s));}
        long through=after;
        for(var user:users) {
            var turn=ledger.contextTurn(g,c,user);turns.add(turn);addSource(sourceMap,guard.source(user));
            if(turn.get("assistant")!=null) {
                var assistant=jdbc.queryForMap("SELECT * FROM kb_agent_message WHERE run_id=? AND role='assistant'",user.get("run_id"));
                addSource(sourceMap,guard.source(assistant));
                if(assistant.get("context_summary_revision") instanceof Number revision) {
                    var ancestor=guard.read(g.actor(),c,revision.longValue());
                    if(ancestor==null)throw conflict("AGENT_SUMMARY_SOURCES");
                    ancestor.sources().forEach(s->addSource(sourceMap,s));
                }
                List<Snapshot> snapshots=guard.decode(assistant.get("evidence"),new TypeReference<>(){});
                for(var snapshot:snapshots) {
                    var old=deps.putIfAbsent(snapshot.evidenceId(),snapshot);
                    if(old!=null && !old.equals(snapshot))throw conflict("AGENT_SUMMARY_SOURCES");
                }
            }
            through=((Number)user.get("sequence")).longValue()+1;
        }
        var sources=List.copyOf(sourceMap.values());
        if(sources.size()>512 || deps.size()>64 || bytes(deps.values())>131072)throw budget();
        String hash=guard.hash(Arrays.asList(latest,base==null?null:base.sourceHash(),sources,through));
        return new Prepared(latest,base,turns,sources,List.copyOf(deps.values()),through,hash,more,users.isEmpty() && base!=null);
    }
    @Transactional(timeout=10)
    public Map<String,Object> write(DelegationBO g,UUID id,Write input) {
        ledger.delegated(g,id,true);
        var p=prepareInternal(g,id,input.runId(),input.retainedTurns(),input.policyVersion());
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM kb_agent_context_summary WHERE run_id=?)",Boolean.class,input.runId())))throw conflict("AGENT_SUMMARY_CONFLICT");
        if(p.revision()!=input.expectedRevision() || !p.hash().equals(input.sourceHash()) || p.turns().isEmpty())throw conflict("AGENT_SUMMARY_CONFLICT");
        if(bytes(input.content())>8192 || input.content().items().isEmpty())throw budget();
        var allowed=new HashSet<UUID>();p.sources().forEach(s->allowed.add(s.messageId()));
        for(var item:input.content().items())if(!allowed.containsAll(item.sourceMessageIds()) || item.text().indexOf(0)>=0 || item.text().contains("[K"))throw conflict("AGENT_SUMMARY_SOURCES");
        // 约束必须是用户原文；旧约束必须保留，不能在增量摘要中悄悄改写。
        for(var item:input.content().constraints()) {
            if(item.sourceMessageIds().size()!=1)throw conflict("AGENT_SUMMARY_SOURCES");
            var rows=jdbc.queryForList("SELECT content FROM kb_agent_message WHERE conversation_id=? AND id=? AND role='user'",id,item.sourceMessageIds().get(0));
            if(rows.isEmpty() || !rows.get(0).get("content").toString().contains(item.text()))throw conflict("AGENT_SUMMARY_SOURCES");
        }
        if(p.base()!=null && !input.content().constraints().containsAll(p.base().content().constraints()))throw conflict("AGENT_SUMMARY_SOURCES");
        long revision=p.revision()+1;
        jdbc.update("INSERT INTO kb_agent_context_summary(conversation_id,revision,run_id,through_sequence,source_hash,sources,content,evidence,model,prompt_version,prompt_hash,policy_version) VALUES(?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?,?,?,?)",
                id,revision,input.runId(),p.through(),p.hash(),guard.encode(p.sources()),guard.encode(input.content()),guard.encode(p.evidence()),input.model(),input.promptVersion(),input.promptHash(),input.policyVersion());
        return Map.of("revision",revision,"throughSequence",p.through());
    }
    @Transactional(timeout=10)
    public Map<String,Object> use(DelegationBO g,UUID id,Use input) {
        ledger.delegated(g,id,true);var p=prepareInternal(g,id,input.runId(),input.retainedTurns(),input.policyVersion());
        if(!p.caughtUp() || p.base().revision()!=input.revision())throw conflict("AGENT_SUMMARY_CONFLICT");
        var run=ledger.get(g,id,input.runId());
        Long old=jdbc.queryForObject("SELECT context_summary_revision FROM kb_agent_message WHERE id=?",Long.class,run.assistantMessageId());
        if(old!=null && old!=input.revision() || old==null && run.checkpointSequence()!=0)throw conflict("AGENT_SUMMARY_CONFLICT");
        jdbc.update("UPDATE kb_agent_message SET context_summary_revision=? WHERE id=?",input.revision(),run.assistantMessageId());
        return Map.of("revision",input.revision());
    }
    private int bytes(Object value){return guard.encode(value).getBytes(StandardCharsets.UTF_8).length;}
    private void addSource(Map<UUID,Source> sources,Source source) {
        var old=sources.putIfAbsent(source.messageId(),source);
        if(old!=null && !old.equals(source))throw conflict("AGENT_SUMMARY_SOURCES");
    }
    private KnowledgeBaseException conflict(String code){return new KnowledgeBaseException(409,code,"Context summary state or sources changed");}
    private KnowledgeBaseException budget(){return new KnowledgeBaseException(413,"CONTEXT_BUDGET","Context summary dependency or content budget exceeded");}
}
