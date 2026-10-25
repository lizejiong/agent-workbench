package com.lzj.rag.backend.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lzj.rag.backend.knowledgebase.KnowledgeBaseException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.system.ApplicationHome;
import org.springframework.stereotype.Component;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

@Component @EnableConfigurationProperties(FormatProperties.class)
public class LocalFormatParser implements DocumentParser {
    private final FormatProperties config;private final ObjectMapper json;private final Semaphore permits;
    public LocalFormatParser(FormatProperties config,ObjectMapper json){this.config=config;this.json=json;permits=new Semaphore(config.concurrency());}
    public String profile(){return "BINARY";}
    public Set<String> mimeTypes(){var types=new HashSet<>(SupportedFiles.OFFICE);types.addAll(SupportedFiles.EXCEL);return Set.copyOf(types);}
    public ParsedDocument parse(String text){throw new KnowledgeBaseException(415,"KB_PARSER_UNAVAILABLE","Binary parser requires a verified file");}
    @Override public ParsedDocument parse(ParserInput input) {
        boolean acquired=false;Process process=null;Path request=null,result=null;
        Instant deadline=Instant.now().plus(config.localTimeout());if(input.deadline().isBefore(deadline))deadline=input.deadline();
        try {
            long wait=Duration.between(Instant.now(),deadline).toMillis();if(wait<=0 || !(acquired=permits.tryAcquire(wait,TimeUnit.MILLISECONDS)))throw timeout();
            request=Files.createTempFile(input.path().toAbsolutePath().getParent(),"kb-parser-request-",".json");
            result=Path.of(request+".result");
            var bounded=new ParserInput(input.path(),input.filename(),input.mime(),input.maxParsedChars(),input.requestId(),deadline);
            // No keys or binary contents enter subprocess arguments/logs.
            var childConfig=json.valueToTree(config);((com.fasterxml.jackson.databind.node.ObjectNode)childConfig).remove(List.of("mineru","vlm"));
            Files.write(request,json.writeValueAsBytes(Map.of("input",bounded,"config",childConfig,"parentPid",ProcessHandle.current().pid())));
            var command=new ArrayList<String>();command.add(Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString());
            command.add("-Xmx"+config.processHeapMb()+"m");command.add("-Dfile.encoding=UTF-8");command.add("-Djava.awt.headless=true");
            var source=new ApplicationHome(ParserProcessMain.class).getSource();
            if(source!=null && source.isFile() && source.getName().endsWith(".jar")) {
                command.add("-Dloader.main="+ParserProcessMain.class.getName());command.add("-cp");command.add(source.getAbsolutePath());command.add("org.springframework.boot.loader.launch.PropertiesLauncher");
            }else {command.add("-cp");command.add(System.getProperty("java.class.path"));command.add(ParserProcessMain.class.getName());}
            command.add(request.toString());command.add(result.toString());
            process=new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            wait=Duration.between(Instant.now(),deadline).toMillis();
            if(wait<=0 || !process.waitFor(wait,TimeUnit.MILLISECONDS))throw timeout();
            if(!Files.exists(result) || Files.size(result)>config.maxResultBytes())throw invalid();
            var response=json.readTree(Files.readAllBytes(result));
            if(response.has("code"))throw new KnowledgeBaseException(response.path("status").asInt(422),response.path("code").asText("KB_PARSE_FAILED"),"Local parsing failed; check source format, formulas and parsing limits");
            var parsed=json.treeToValue(response.path("document"),ParsedDocument.class);
            if(parsed==null || parsed.text()==null || parsed.blocks()==null || parsed.text().length()>input.maxParsedChars())throw invalid();return parsed;
        }catch(KnowledgeBaseException e){throw e;}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw timeout();}
        catch(Exception e){throw invalid();}
        finally {
            if(process!=null && process.isAlive()){process.destroyForcibly();try{process.waitFor(2,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
            remove(request);remove(result);if(acquired)permits.release();
        }
    }
    public void validatePdf(ParserInput input){parse(new ParserInput(input.path(),input.filename(),"application/x-kb-pdf-inspection",input.maxParsedChars(),input.requestId(),input.deadline()));}
    private void remove(Path path){if(path!=null)try{Files.deleteIfExists(path);}catch(java.io.IOException e){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Parser temporary artifact awaits bounded spool sweep");}}
    private static KnowledgeBaseException timeout(){return new KnowledgeBaseException(504,"KB_PARSE_TIMEOUT","Local parsing timed out or was interrupted");}
    private static KnowledgeBaseException invalid(){return new KnowledgeBaseException(422,"KB_PARSE_FAILED","Local parser failed or exceeded its bounded result/heap budget");}
}
