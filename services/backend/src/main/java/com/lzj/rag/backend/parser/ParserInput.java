package com.lzj.rag.backend.parser;
import java.nio.file.Path;
import java.time.Instant;

public record ParserInput(Path path,String filename,String mime,int maxParsedChars,String requestId,Instant deadline) {
    public String text(){try{return SupportedFiles.text(path,maxParsedChars);}catch(java.io.IOException e){throw new com.lzj.rag.backend.knowledgebase.KnowledgeBaseException(422,"KB_SOURCE_INVALID","Source is not valid bounded UTF-8 text");}}
}
