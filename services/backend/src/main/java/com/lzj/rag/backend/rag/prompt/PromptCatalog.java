package com.lzj.rag.backend.rag.prompt;

import com.lzj.rag.backend.model.catalog.EmbeddingCatalog;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.ConcurrentHashMap;

/** UTF-8 中文模板按版本读取并固定快照，避免请求中途改变提示词。 */
@Component
@EnableConfigurationProperties(PromptProperties.class)
public class PromptCatalog {
    public record Template(String version, String sha256, String content) {}
    private final PromptProperties config;
    private final ConcurrentHashMap<String,Template> templates=new ConcurrentHashMap<>();
    public PromptCatalog(PromptProperties config) { this.config=config; }
    public Template query() { return get(config.queryVersion()); }
    public Template get(String version) {
        if(version==null || !version.matches("[a-z0-9-]{1,80}")) throw new IllegalArgumentException("Invalid prompt version");
        return templates.computeIfAbsent(version,this::load);
    }
    private Template load(String version) {
        try {
            byte[] bytes;
            if(config.directory()!=null && !config.directory().isBlank()) {
                var root=Path.of(config.directory()).toRealPath();
                var file=root.resolve(version+".txt").toRealPath();
                if(!file.startsWith(root) || Files.size(file)>32768) throw new IllegalArgumentException("Invalid prompt file");
                bytes=Files.readAllBytes(file);
            } else try(var input=new ClassPathResource("rag/prompts/"+version+".txt").getInputStream()) {
                bytes=input.readNBytes(32769);
            }
            String content=new String(bytes,StandardCharsets.UTF_8);
            if(bytes.length>32768 || content.isBlank()) throw new IllegalArgumentException("Invalid prompt content");
            return new Template(version,EmbeddingCatalog.sha256(content),content);
        } catch(java.io.IOException e) { throw new IllegalStateException("Cannot load prompt version "+version,e); }
    }
}
