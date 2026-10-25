package com.lzj.rag.backend.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lzj.rag.framework.starter.web.result.Results;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.ArrayList;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KnowledgeBaseSecurityProperties.class)
public class KnowledgeBaseSecurityConfiguration {
    @Bean
    UserDetailsService knowledgeBaseUsers(KnowledgeBaseSecurityProperties properties) {
        var encoder = new BCryptPasswordEncoder();
        var usernames = new HashSet<String>();
        var users = new ArrayList<UserDetails>();
        for (var account : properties.accounts()) {
            int bytes = account.password().getBytes(StandardCharsets.UTF_8).length;
            if (bytes < 16 || bytes > 72) {
                throw new IllegalStateException("KB account passwords must contain 16 to 72 UTF-8 bytes");
            }
            if (!usernames.add(account.username().toLowerCase(java.util.Locale.ROOT))) {
                throw new IllegalStateException("KB account usernames must be unique ignoring case");
            }
            users.add(new KnowledgeBasePrincipal(account.username(), "{bcrypt}" + encoder.encode(account.password()),
                    account.tenantId(), account.userId(), account.operator()));
        }
        // InMemoryUserDetailsManager copies UserDetails on lookup, so retain our scoped principal explicitly.
        var manager = new InMemoryUserDetailsManager(users);
        var identities = users.stream().map(KnowledgeBasePrincipal.class::cast)
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        p -> p.getUsername().toLowerCase(java.util.Locale.ROOT), p -> p));
        return username -> {
            var authenticated = manager.loadUserByUsername(username);
            var identity = identities.get(authenticated.getUsername().toLowerCase(java.util.Locale.ROOT));
            return new KnowledgeBasePrincipal(authenticated.getUsername(), authenticated.getPassword(),
                    identity.tenantId(), identity.userId(),identity.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("ROLE_KB_OPERATOR")));
        };
    }

    @Bean
    SecurityFilterChain knowledgeBaseSecurity(HttpSecurity http, ObjectMapper json) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        .requestMatchers("/actuator/metrics", "/actuator/metrics/**").hasRole("KB_OPERATOR")
                        .requestMatchers("/actuator/**").denyAll()
                        .anyRequest().hasRole("KB_USER"))
                .httpBasic(basic -> basic.authenticationEntryPoint((request, response, error) -> {
                    response.setHeader("WWW-Authenticate", "Basic realm=\"knowledge-base\"");
                    writeError(json, response, 401, "KB_UNAUTHENTICATED", "Authentication required");
                }))
                .exceptionHandling(errors -> errors.accessDeniedHandler((request, response, error) ->
                        writeError(json, response, 403, "KB_FORBIDDEN", "Access denied")))
                .build();
    }

    private static void writeError(ObjectMapper json, HttpServletResponse response, int status,
                                   String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        json.writeValue(response.getOutputStream(), Results.failure(code, message));
    }
}
