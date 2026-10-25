package com.lzj.rag.backend.parser;

import com.lzj.rag.backend.knowledgebase.KnowledgeBaseException;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class ParserRegistry {
    private final Map<String,DocumentParser> parsers = new HashMap<>();
    public ParserRegistry(List<DocumentParser> candidates) {
        for (var parser : candidates) for (String mime : parser.mimeTypes()) {
            if (parsers.putIfAbsent(parser.parseProfile()+":"+parser.profile()+":"+mime, parser) != null)
                throw new IllegalStateException("Duplicate parser claim for profile/MIME: "+parser.profile()+"/"+mime);
        }
    }
    public DocumentParser resolve(String mime, String requested, String originalFilename) {
        return resolve(mime,requested,originalFilename,"fast");
    }
    public DocumentParser resolve(String mime,String requested,String originalFilename,String parseProfile) {
        String ext=SupportedFiles.extension(originalFilename);
        String profile=requested.equals("AUTO")?(ext.equals("txt")?"TEXT":Set.of("md","markdown").contains(ext)?"MARKDOWN":ext.equals("csv")?"CSV":"BINARY"):requested;
        var parser = parsers.get(parseProfile+":"+profile+":"+mime);
        // Declared aliases only; failures from a selected parser never trigger a downgrade.
        if(parser==null && parseProfile.equals("fidelity") && (Set.of("TEXT","MARKDOWN","CSV").contains(profile)
                || SupportedFiles.EXCEL.contains(mime) || SupportedFiles.IMAGES.contains(mime)))parser=parsers.get("fast:"+profile+":"+mime);
        if (parser == null) throw new KnowledgeBaseException(415,"KB_PARSER_UNAVAILABLE","No parser registered for this MIME and profile");
        return parser;
    }
}
