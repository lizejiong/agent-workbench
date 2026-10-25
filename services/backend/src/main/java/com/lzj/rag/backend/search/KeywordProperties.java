package com.lzj.rag.backend.search;
import com.lzj.rag.backend.model.EmbeddingCatalog;
import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;
import java.net.URI;
import java.time.Duration;
import java.util.Set;

@Validated @ConfigurationProperties("kb.keyword")
public record KeywordProperties(@DefaultValue("false") boolean enabled,@DefaultValue("http://localhost:9200") String endpoint,
        String apiKey,@DefaultValue("kb-chunks-v1") @Pattern(regexp="[a-z][a-z0-9_-]{2,100}") String index,
        @DefaultValue("cjk") @Pattern(regexp="cjk|smartcn|ik_max_word|ik_smart") String analyzer,
        @DefaultValue("3s") Duration callTimeout,@DefaultValue("1m") Duration lease,
        @DefaultValue("2000") @Min(1) @Max(20000) int maxScopeDocuments,
        @DefaultValue("100") @Min(1) @Max(500) int batchSize,
        @DefaultValue("5") @Min(1) @Max(10) int maxAttempts) {
    public KeywordProperties {
        if(enabled) {var uri=URI.create(endpoint);if(!Set.of("http","https").contains(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null || !(uri.getPath().isEmpty() || uri.getPath().equals("/")))throw new IllegalArgumentException("Keyword endpoint must be a trusted HTTP(S) origin");}
        if(callTimeout!=null && lease!=null && (callTimeout.isNegative() || callTimeout.isZero() || callTimeout.compareTo(Duration.ofSeconds(15))>0 || lease.compareTo(Duration.ofSeconds(15))<0 || lease.compareTo(Duration.ofMinutes(5))>0))throw new IllegalArgumentException("Keyword call timeout <=15s, lease 15s..5m required");
    }
    public String target(){return EmbeddingCatalog.sha256(endpoint+"/"+index+"/"+analyzer+"/schema1");}
}
