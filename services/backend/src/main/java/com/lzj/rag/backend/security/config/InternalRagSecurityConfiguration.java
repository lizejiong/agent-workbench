package com.lzj.rag.backend.security.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lzj.rag.backend.rag.config.InternalRagProperties;
import com.lzj.rag.backend.model.catalog.EmbeddingCatalog;
import com.lzj.rag.framework.starter.web.result.Results;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;

@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties(InternalRagProperties.class)
public class InternalRagSecurityConfiguration {
    public record ServiceIdentity(String serviceId) {}

    @Bean @Order(1)
    SecurityFilterChain internalRagSecurity(HttpSecurity http,InternalRagProperties config,ObjectMapper json)throws Exception {
        var authentication=new OncePerRequestFilter() {
            protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws ServletException,IOException {
                if(!config.enabled()){failure(res,503,"RAG_INTERNAL_DISABLED");return;}
                String service=req.getHeader("X-Rag-Service"),auth=req.getHeader("Authorization");
                String expected=service==null?null:config.serviceTokenHashes().get(service);
                if(Collections.list(req.getHeaders("Authorization")).size()!=1 || Collections.list(req.getHeaders("X-Rag-Service")).size()!=1
                        || expected==null || auth==null || !auth.matches("Bearer [A-Za-z0-9_-]{32,256}")
                        || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                            EmbeddingCatalog.sha256(auth.substring(7)).getBytes(StandardCharsets.US_ASCII))) {
                    failure(res,401,"RAG_SERVICE_UNAUTHENTICATED");return;
                }
                var context=SecurityContextHolder.createEmptyContext();
                context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(new ServiceIdentity(service),null,
                        AuthorityUtils.createAuthorityList("ROLE_INTERNAL_RAG")));
                SecurityContextHolder.setContext(context);
                try{chain.doFilter(req,res);}finally{SecurityContextHolder.clearContext();}
            }
            private void failure(HttpServletResponse res,int status,String code)throws IOException {
                res.setStatus(status);res.setContentType("application/json");res.setHeader("Cache-Control","no-store");
                json.writeValue(res.getOutputStream(),Results.failure(code,"Internal retrieval authentication unavailable"));
            }
        };
        return http.securityMatcher("/internal/**").csrf(c->c.disable()).requestCache(c->c.disable())
                .sessionManagement(c->c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(authentication,BasicAuthenticationFilter.class)
                .authorizeHttpRequests(a->a.dispatcherTypeMatchers(DispatcherType.ASYNC,DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.POST,"/internal/rag/retrieve","/internal/rag/requests/*/cancel")
                        .hasRole("INTERNAL_RAG")
                        .requestMatchers(HttpMethod.POST,"/internal/agent/conversations/*/runs").hasRole("INTERNAL_RAG")
                        .requestMatchers(HttpMethod.GET,"/internal/agent/conversations/*/history","/internal/agent/conversations/*/runs/*").hasRole("INTERNAL_RAG")
                        .requestMatchers(HttpMethod.PATCH,"/internal/agent/conversations/*/runs/*").hasRole("INTERNAL_RAG")
                        .anyRequest().denyAll())
                .build();
    }
}
