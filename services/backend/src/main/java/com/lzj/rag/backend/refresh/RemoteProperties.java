package com.lzj.rag.backend.refresh;

import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;
import java.time.Duration;
import java.util.List;

@Validated @ConfigurationProperties("kb.remote")
public record RemoteProperties(@DefaultValue("false") boolean enabled,
        List<String> allowedHosts, List<String> trustedPrivateHosts,
        @DefaultValue("443,80") List<Integer> allowedPorts,
        @DefaultValue("false") boolean allowHttp,
        @DefaultValue("5s") Duration connectTimeout,@DefaultValue("10s") Duration readTimeout,
        @DefaultValue("30s") Duration downloadTimeout,@DefaultValue("3") @Min(0) @Max(8) int maxRedirects,
        @DefaultValue("20971520") @Min(1) @Max(1073741824) long maxDownloadBytes,
        @DefaultValue("2") @Min(1) @Max(8) int concurrency,
        @DefaultValue("45s") Duration lease,@DefaultValue("15m") Duration runDeadline,
        @DefaultValue("3") @Min(1) @Max(5) int maxAttempts) {
    public RemoteProperties {
        allowedHosts=allowedHosts==null?List.of():List.copyOf(allowedHosts);
        trustedPrivateHosts=trustedPrivateHosts==null?List.of():List.copyOf(trustedPrivateHosts);
        if(allowedPorts==null)allowedPorts=List.of(443,80);else allowedPorts=List.copyOf(allowedPorts);
        for(var duration:List.of(connectTimeout,readTimeout,downloadTimeout,lease,runDeadline))
            if(duration==null || duration.isNegative() || duration.isZero())throw new IllegalArgumentException("Remote timeouts must be positive");
        if(downloadTimeout.compareTo(Duration.ofMinutes(2))>0 || lease.compareTo(Duration.ofSeconds(15))<0 || runDeadline.compareTo(Duration.ofMinutes(30))>0)
            throw new IllegalArgumentException("Remote download <=2m, lease >=15s and run deadline <=30m required");
        if(!allowedHosts.containsAll(trustedPrivateHosts) || allowedPorts.isEmpty() || allowedPorts.stream().anyMatch(p->p<1 || p>65535))
            throw new IllegalArgumentException("Private hosts must be explicitly present in allowedHosts; ports must be valid");
        for(String host:allowedHosts)if(!host.matches("[A-Za-z0-9.:-]{1,253}") || host.contains("*"))throw new IllegalArgumentException("Use exact remote host names, without wildcard or URL");
    }
}
