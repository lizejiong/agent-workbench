package com.lzj.rag.backend.agent.service;

import com.lzj.rag.backend.agent.controller.AgentContracts.*;
import com.lzj.rag.backend.knowledgebase.exception.KnowledgeBaseException;
import java.util.*;
import java.util.stream.Collectors;

/** 校验有序增量快照；公开工具结果从当前可访问证据组装。 */
public final class ConversationTimeline {
    private ConversationTimeline() {}
    private static final Set<String> TERMINAL=Set.of("done","failed","interrupted");

    public static List<Block> validate(Checkpoint input,List<Block> previous,long previousSeq) {
        if(input.blocks()==null) {
            if(!previous.isEmpty())throw invalid();
            return List.of(); // 兼容升级前的 Python 客户端，不能清除已有时间线。
        }
        var blocks=input.blocks();
        if(input.eventSeq()==null || input.eventSeq()<previousSeq || blocks.size()<previous.size())throw invalid();
        var tools=input.tools().stream().collect(Collectors.toMap(Tool::toolCallId,t->t,(a,b)->{throw invalid();}));
        var snapshots=input.evidence().stream().collect(Collectors.toMap(Snapshot::evidenceId,s->s,(a,b)->{throw invalid();}));
        var ids=new HashSet<String>();var calls=new HashSet<String>();
        var text=new ArrayList<String>();
        for(int i=0;i<blocks.size();i++) {
            Block b=blocks.get(i);
            if(!ids.add(b.blockId()))throw invalid();
            if(b.kind().equals("answer")) {
                if(b.text()==null || !Set.of("streaming","done","interrupted").contains(b.status()))throw invalid();
                text.add(b.text());
            } else if(b.kind().equals("tool")) {
                if(b.text()!=null || !calls.add(b.toolCallId()) || !tools.containsKey(b.toolCallId())
                        || !Objects.equals(b.name(),"search_knowledge") || b.batchId()==null || b.callIndex()==null
                        || b.status().equals("streaming") || b.evidenceIds()==null || !snapshots.keySet().containsAll(b.evidenceIds()))throw invalid();
                if(!b.status().equals("pending") && b.startedAt()==null)throw invalid();
                if(TERMINAL.contains(b.status()) && b.durationMs()==null)throw invalid();
            } else if(b.text()==null || b.text().length()>1000 || !TERMINAL.contains(b.status()))throw invalid();
            if(!b.kind().equals("tool") && (b.toolCallId()!=null || b.name()!=null || b.arguments()!=null
                    || b.batchId()!=null || b.callIndex()!=null || b.evidenceIds()!=null))throw invalid();
            if(i<previous.size()) {
                Block old=previous.get(i);
                if(!old.blockId().equals(b.blockId()) || !old.kind().equals(b.kind())
                        || !Objects.equals(old.toolCallId(),b.toolCallId()) || !Objects.equals(old.name(),b.name())
                        || !Objects.equals(old.batchId(),b.batchId()) || !Objects.equals(old.callIndex(),b.callIndex())
                        || !Objects.equals(old.arguments(),b.arguments()))throw invalid();
                if(old.text()!=null && (b.text()==null || !b.text().startsWith(old.text())
                        || i<previous.size()-1 && !b.text().equals(old.text())))throw invalid();
                if(old.kind().equals("tool") && (TERMINAL.contains(old.status()) && !old.equals(b)
                        || old.status().equals("running") && b.status().equals("pending")
                        || old.startedAt()!=null && !old.startedAt().equals(b.startedAt())))throw invalid();
            }
        }
        if(!String.join("\n\n",text).equals(input.content()))throw invalid();
        return List.copyOf(blocks);
    }

    public static List<Map<String,Object>> visible(List<Block> blocks,List<Tool> tools,
            List<Map<String,Object>> evidence,String runStatus) {
        var byCall=tools.stream().collect(Collectors.toMap(Tool::toolCallId,t->t,(a,b)->a));
        var result=new ArrayList<Map<String,Object>>();
        for(Block b:blocks) {
            var row=new LinkedHashMap<String,Object>();
            String status=b.status();
            if(!runStatus.equals("RUNNING") && Set.of("streaming","pending","running").contains(status))
                status=b.kind().equals("answer") && runStatus.equals("COMPLETED")?"done":"interrupted";
            row.put("blockId",b.blockId());row.put("kind",b.kind());row.put("status",status);
            if(b.text()!=null)row.put("text",b.text());
            if(b.kind().equals("tool")) {
                row.put("toolCallId",b.toolCallId());row.put("name",b.name());row.put("displayName","知识库检索");
                row.put("batchId",b.batchId());row.put("callIndex",b.callIndex());
                row.put("arguments",b.arguments());row.put("startedAt",b.startedAt());row.put("durationMs",b.durationMs());
                row.put("evidenceIds",b.evidenceIds());
                var tool=byCall.get(b.toolCallId());
                if(tool!=null) {
                    row.put("retrievalStatus",tool.status());row.put("degraded",tool.degraded());
                    row.put("strategy",tool.strategy());row.put("toolTruncated",tool.toolTruncated());
                    row.put("errorCode",tool.errorCode());
                }
                var selected=evidence.stream().filter(e->b.evidenceIds().contains(e.get("evidenceId"))).toList();
                if(TERMINAL.contains(status))row.put("result",Map.of("evidence",selected));
            } else if(b.errorCode()!=null)row.put("errorCode",b.errorCode());
            result.add(row);
        }
        return result;
    }
    private static KnowledgeBaseException invalid(){return new KnowledgeBaseException(409,"AGENT_BLOCK_CONFLICT","Invalid timeline snapshot or transition");}
}
