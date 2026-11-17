package com.lzj.rag.backend.tools;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.net.URI;
import java.util.List;

@ConfigurationProperties("kb.tools")
public record ToolProperties(@DefaultValue("http://127.0.0.1:8090") String agentOrigin,
        @DefaultValue("") String agentToken, @DefaultValue List<String> allowedOrigins) {
    public ToolProperties {
        allowedOrigins=allowedOrigins==null?List.of():List.copyOf(allowedOrigins);
        URI u=URI.create(agentOrigin);
        if(!List.of("http","https").contains(u.getScheme()) || u.getHost()==null || u.getUserInfo()!=null
            || u.getRawQuery()!=null || u.getFragment()!=null || !(u.getPath().isEmpty() || u.getPath().equals("/")))
            throw new IllegalArgumentException("Invalid agent origin");
    }
    @Override public String toString(){return "ToolProperties[agentOrigin="+agentOrigin+", agentToken=[redacted]]";}
}
