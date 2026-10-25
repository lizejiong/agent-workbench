package com.lzj.rag.backend.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lzj.rag.backend.knowledgebase.KnowledgeBaseException;
import java.nio.file.*;
import java.util.Map;

/** Same application distribution, isolated heap and hard process deadline; never a second HTTP service. */
public final class ParserProcessMain {
    private ParserProcessMain() { }
    public static void main(String[] args)throws Exception {
        var json=new ObjectMapper().findAndRegisterModules();var request=json.readTree(Files.readAllBytes(Path.of(args[0])));
        Object output;
        try {
            var input=json.treeToValue(request.path("input"),ParserInput.class);
            var config=json.treeToValue(request.path("config"),FormatProperties.class);
            long parent=request.path("parentPid").asLong();
            var watchdog=new Thread(()->{while(true)try {
                Thread.sleep(1000);
                if(java.time.Instant.now().isAfter(input.deadline()) || ProcessHandle.of(parent).map(p->!p.isAlive()).orElse(true))Runtime.getRuntime().halt(124);
            }catch(InterruptedException e){return;}},"parser-parent-deadline");watchdog.setDaemon(true);watchdog.start();
            output=Map.of("document",LocalFormatExtractor.parse(input,config));
        }catch(Throwable error) {
            String code=error instanceof KnowledgeBaseException kb?kb.code():"KB_PARSE_FAILED";
            output=Map.of("status",error instanceof KnowledgeBaseException kb?kb.status():422,"code",code,"message","Local parsing failed or exceeded its limits; inspect the format matrix and source file");
        }
        Files.write(Path.of(args[1]),json.writeValueAsBytes(output));
    }
}
