package com.lzj.rag.backend.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.time.Duration;
import java.util.Map;

/** 服务凭据只授予调用权限；数据访问还必须具有用户签发的有效委托。 */
@ConfigurationProperties("kb.rag.internal")
public record InternalRagProperties(@DefaultValue("false") boolean enabled,
        Map<String,String> serviceTokenHashes,@DefaultValue("5m") Duration delegationTtl,
        @DefaultValue("4") int concurrency) {
    public InternalRagProperties {
        serviceTokenHashes=serviceTokenHashes==null?Map.of():Map.copyOf(serviceTokenHashes);
        if(concurrency<1 || concurrency>32 || delegationTtl==null || delegationTtl.isNegative()
                || delegationTtl.isZero() || delegationTtl.compareTo(Duration.ofMinutes(15))>0)
            throw new IllegalArgumentException("Invalid internal RAG limits");
        serviceTokenHashes.forEach((id,hash)->{
            if(!id.matches("[a-z0-9-]{1,80}") || !hash.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Internal service IDs and SHA-256 hashes must be valid");
        });
        if(enabled && serviceTokenHashes.isEmpty())throw new IllegalArgumentException("Internal RAG requires service credentials");
    }
}
