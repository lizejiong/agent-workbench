package com.lzj.rag.backend.parser;
import org.springframework.stereotype.Component;
import java.util.Set;

@Component
public class CsvDocumentParser implements DocumentParser {
    private final LocalFormatParser local;
    public CsvDocumentParser(LocalFormatParser local){this.local=local;}
    public String profile(){return "CSV";}
    public Set<String> mimeTypes(){return Set.of("text/plain","text/csv","application/csv");}
    public ParsedDocument parse(String text){throw new com.lzj.rag.backend.knowledgebase.KnowledgeBaseException(415,"KB_PARSER_UNAVAILABLE","CSV parser requires verified file input");}
    @Override public ParsedDocument parse(ParserInput input){return local.parse(input);}
}
