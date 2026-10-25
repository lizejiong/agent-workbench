package com.lzj.rag.backend.parser;

import org.springframework.stereotype.Component;
import java.util.*;
import static com.lzj.rag.backend.parser.ParsedDocument.*;

@Component
public class TextDocumentParser implements DocumentParser {
    public String profile() { return "TEXT"; }
    public Set<String> mimeTypes() { return Set.of("text/plain"); }
    public ParsedDocument parse(String text) {
        var blocks = new ArrayList<Block>();
        String[] lines = text.split("\n", -1); var paragraph = new StringBuilder(); int start = 1;
        for (int i=0;i<=lines.length;i++) {
            String line = i==lines.length ? "" : lines[i];
            if (line.isBlank()) {
                if (!paragraph.isEmpty()) {
                    blocks.add(new Block(blocks.size(),BlockType.PARAGRAPH,paragraph.toString().strip(),List.of(),
                            Map.of("lineStart",start,"lineEnd",i),List.of(),List.of())); paragraph.setLength(0);
                }
            } else { if (paragraph.isEmpty()) start=i+1; else paragraph.append('\n'); paragraph.append(line); }
        }
        return new ParsedDocument(text,List.copyOf(blocks),"utf8-paragraph-v1");
    }
}
