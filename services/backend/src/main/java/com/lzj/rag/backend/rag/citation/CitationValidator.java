package com.lzj.rag.backend.rag.citation;

import com.lzj.rag.backend.rag.service.bo.*;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.regex.Pattern;

/** 仅校验本次证据编号，不能判断引用是否支持正文断言。 */
@Component
public class CitationValidator {
    public record Check(String status,List<RagAnswerBO.CitationBO> citations) {}
    private static final Pattern MARKER=Pattern.compile("\\[(E[^\\]\\r\\n]{0,63})\\]");
    public Check validate(String answer,List<EvidenceSnapshotBO> evidence) {
        var allowed=new HashMap<String,EvidenceSnapshotBO>();evidence.forEach(e->allowed.put(e.evidenceId(),e));
        var ids=new LinkedHashSet<String>();var matcher=MARKER.matcher(answer);boolean invalid=false;
        while(matcher.find()){String id=matcher.group(1);if(!id.matches("E[1-9][0-9]{0,3}") || !allowed.containsKey(id))invalid=true;else ids.add(id);}
        var citations=ids.stream().map(id->{var e=allowed.get(id);return new RagAnswerBO.CitationBO(id,e.documentId(),e.chunkId(),e.generation(),e.snapshotHash());}).toList();
        return new Check(invalid?"UNKNOWN_REFERENCE":ids.isEmpty()?"MISSING_REFERENCE":"VALID",citations);
    }
}
