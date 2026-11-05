package com.lzj.rag.backend.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lzj.rag.backend.agent.controller.AgentContracts.Snapshot;
import com.lzj.rag.backend.model.catalog.EmbeddingCatalog;
import com.lzj.rag.backend.search.repository.EvidenceRepository;
import com.lzj.rag.backend.search.service.bo.SearchBO;
import com.lzj.rag.backend.security.model.KnowledgeBasePrincipal;
import org.springframework.stereotype.Component;
import java.util.*;

/** 历史/模型输入均复用检索的当前有效证据回表规则，失效时整体隐藏派生回答。 */
@Component
public class ConversationEvidence {
    private final EvidenceRepository records;
    private final ObjectMapper json;
    public ConversationEvidence(EvidenceRepository records,ObjectMapper json){this.records=records;this.json=json;}

    public List<Map<String,Object>> visible(KnowledgeBasePrincipal actor,List<UUID> kbs,List<UUID> docs,List<Snapshot> saved) {
        if(saved.isEmpty())return List.of();
        var current=records.hydrate(actor,new SearchBO("?",kbs,docs,5,null),saved.stream().map(Snapshot::chunkId).distinct().toList());
        var result=new ArrayList<Map<String,Object>>();
        for(var s:saved) {
            var live=current.get(s.chunkId());
            if(live==null || !live.kbId().equals(s.kbId()) || !live.documentId().equals(s.documentId())
                    || live.generation()!=s.generation() || !EmbeddingCatalog.sha256(live.content()).equals(s.sourceContentHash())
                    || !EmbeddingCatalog.sha256(s.content()).equals(s.snapshotHash()) || !live.content().startsWith(s.content())
                    || !json.valueToTree(live.sourceMetadata()).equals(json.valueToTree(s.sourceMetadata())))return List.of();
            Map<String,Object> row=new LinkedHashMap<>();
            row.put("evidenceId",s.evidenceId());row.put("kbId",s.kbId());row.put("documentId",s.documentId());row.put("chunkId",s.chunkId());
            row.put("generation",s.generation());row.put("documentName",live.documentName());row.put("content",s.content());
            row.put("sourceMetadata",s.sourceMetadata());row.put("snapshotHash",s.snapshotHash());row.put("sourceContentHash",s.sourceContentHash());
            row.put("fileUrl","/api/documents/"+s.documentId()+"/file");row.put("truncated",s.truncated());
            row.put("toolTruncated",s.toolTruncated());row.put("versions",s.versions());result.add(row);
        }
        return List.copyOf(result);
    }
}
