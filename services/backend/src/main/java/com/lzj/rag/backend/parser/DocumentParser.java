package com.lzj.rag.backend.parser;
import java.util.Set;

public interface DocumentParser {
    String profile();
    Set<String> mimeTypes();
    ParsedDocument parse(String utf8Text);
    default String parseProfile(){return "fast";}
    default ParsedDocument parse(ParserInput input){return parse(input.text());}
}
