package com.lzj.rag.backend.parser;
import com.lzj.rag.backend.storage.StorageProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.nio.file.*;
import java.time.*;
import java.util.UUID;

@Component
public class ParserSpoolCleanup {
    private final StorageProperties config;private final JdbcTemplate jdbc;
    public ParserSpoolCleanup(StorageProperties config,JdbcTemplate jdbc){this.config=config;this.jdbc=jdbc;}
    @Scheduled(fixedDelayString="${kb.formats.spool-sweep-delay:300000}",initialDelayString="${kb.formats.spool-sweep-delay:300000}")
    public void scan() {
        var directory=config.tempDirectory();if(directory==null || directory.toString().isBlank())directory=Path.of(System.getProperty("java.io.tmpdir"));
        if(!Files.isDirectory(directory))return;
        try(var paths=Files.list(directory)) {
            for(var file:paths.filter(p->p.getFileName().toString().startsWith("kb-source-") || p.getFileName().toString().startsWith("kb-parser-request-") || p.getFileName().toString().startsWith("kb-remote-") || p.getFileName().toString().startsWith("kb-upload-"))
                    .filter(p->Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)).filter(this::oldEnough).limit(256).toList()) {
                String name=file.getFileName().toString();
                if(name.startsWith("kb-source-")) {
                    if(name.length()<46)continue;UUID job;try{job=UUID.fromString(name.substring(10,46));}catch(IllegalArgumentException invalid){continue;}
                    if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM kb_ingestion_job WHERE id=? AND status='RUNNING' AND lease_until>clock_timestamp())",Boolean.class,job)))continue;
                }
                Files.deleteIfExists(file);
            }
        }catch(Exception error){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Parser spool sweep deferred; files remain eligible for a later bounded scan");}
    }
    private boolean oldEnough(Path path){try{return Files.getLastModifiedTime(path,LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(Instant.now().minus(Duration.ofMinutes(30)));}catch(java.io.IOException e){return false;}}
}
