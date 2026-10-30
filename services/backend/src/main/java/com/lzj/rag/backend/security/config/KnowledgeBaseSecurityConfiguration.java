package com.lzj.rag.backend.security.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lzj.rag.backend.security.model.KnowledgeBasePrincipal;
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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;

/** 装配业务认证与访问规则，并使用公共响应契约返回认证错误。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KnowledgeBaseSecurityProperties.class)
public class KnowledgeBaseSecurityConfiguration {

    /**
     * 校验账号配置并建立包含租户及业务权限的认证用户服务。
     *
     * @param properties 业务账号配置，包含租户、用户及可选运营权限。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @Bean
    UserDetailsService knowledgeBaseUsers(KnowledgeBaseSecurityProperties properties) {
        // encoder：认证账号使用的密码编码器。
        var encoder = new BCryptPasswordEncoder();
        // usernames：用于校验账号名称唯一性的集合。
        var usernames = new HashSet<String>();
        // users：构建完成的认证用户集合。
        var users = new ArrayList<UserDetails>();
        // account：当前处理的账号配置。
        for (var account : properties.accounts()) {
            // bytes：当前累计或待发送内容的字节数。
            int bytes = account.password().getBytes(StandardCharsets.UTF_8).length;
            if (bytes < 16 || bytes > 72) {
                throw new IllegalStateException(
                        "KB account passwords must contain 16 to 72 UTF-8 bytes");
            }
            if (!usernames.add(account.username().toLowerCase(java.util.Locale.ROOT))) {
                throw new IllegalStateException(
                        "KB account usernames must be unique ignoring case");
            }
            users.add(
                    new KnowledgeBasePrincipal(
                            account.username(),
                            "{bcrypt}" + encoder.encode(account.password()),
                            account.tenantId(),
                            account.userId(),
                            account.operator()));
        }
        // manager：认证用户管理器或业务事务管理器。
        // 内存用户管理器查询时会复制 UserDetails，因此显式恢复携带租户和用户范围的业务身份。
        var manager = new InMemoryUserDetailsManager(users);
        // p：已建立的认证用户身份，用于构造按小写用户名索引的查找表。
        // p：已建立的认证用户身份，用于构造按小写用户名索引的查找表。
        // identities：用于校验模型身份一致性的集合。
        var identities =
                users.stream()
                        .map(KnowledgeBasePrincipal.class::cast)
                        .collect(
                                java.util.stream.Collectors.toUnmodifiableMap(
                                        p -> p.getUsername().toLowerCase(java.util.Locale.ROOT),
                                        p -> p));
        return username -> {
            // username：认证用户名。
            // authenticated：已解析的业务认证身份。
            var authenticated = manager.loadUserByUsername(username);
            // identity：本次响应中需要校验的模型或配置身份。
            var identity =
                    identities.get(authenticated.getUsername().toLowerCase(java.util.Locale.ROOT));
            // a：当前认证用户的一项权限，用于判断运营角色。
            return new KnowledgeBasePrincipal(
                    authenticated.getUsername(),
                    authenticated.getPassword(),
                    identity.tenantId(),
                    identity.userId(),
                    identity.getAuthorities().stream()
                            .anyMatch(a -> a.getAuthority().equals("ROLE_KB_OPERATOR")));
        };
    }

    /**
     * 配置业务访问规则、Basic 认证及统一认证失败响应。
     *
     * @param http 配置认证、授权和错误响应的 HTTP 安全构建器。
     * @param json 用于业务请求、持久化快照与元数据转换的 JSON 映射器。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @Bean
    SecurityFilterChain knowledgeBaseSecurity(HttpSecurity http, ObjectMapper json)
            throws Exception {
        // request：当前入口接收的请求参数。
        // response：当前 HTTP 响应或外部服务返回结果。
        // error：本次处理捕获的异常，用于归类失败原因。
        // errors：认证及授权异常处理配置构建器。
        // basic：HTTP Basic 认证配置构建器。
        // auth：URL 授权规则配置构建器。
        // cache：HTTP 请求缓存配置构建器。
        // session：HTTP 会话管理配置构建器。
        // csrf：CSRF 防护配置构建器。
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .authorizeHttpRequests(
                        auth ->
                                auth.dispatcherTypeMatchers(DispatcherType.ERROR)
                                        .permitAll()
                                        .requestMatchers("/actuator/health")
                                        .permitAll()
                                        .requestMatchers(
                                                "/actuator/metrics", "/actuator/metrics/**")
                                        .hasRole("KB_OPERATOR")
                                        .requestMatchers("/actuator/**")
                                        .denyAll()
                                        .anyRequest()
                                        .hasRole("KB_USER"))
                .httpBasic(
                        basic ->
                                basic.authenticationEntryPoint(
                                        (request, response, error) -> {
                                            // request：当前入口接收的请求参数。
                                            // response：当前 HTTP 响应或外部服务返回结果。
                                            // error：本次处理捕获的异常，用于归类失败原因。
                                            response.setHeader(
                                                    "WWW-Authenticate",
                                                    "Basic realm=\"knowledge-base\"");
                                            writeError(
                                                    json,
                                                    response,
                                                    401,
                                                    "KB_UNAUTHENTICATED",
                                                    "Authentication required");
                                        }))
                .exceptionHandling(
                        errors ->
                                errors.accessDeniedHandler(
                                        (request, response, error) ->
                                                writeError(
                                                        json,
                                                        response,
                                                        403,
                                                        "KB_FORBIDDEN",
                                                        "Access denied")))
                .build();
    }

    /**
     * 以公共响应格式写入认证或授权错误，同时携带请求标识。
     *
     * @param json 用于业务请求、持久化快照与元数据转换的 JSON 映射器。
     * @param response 需要写入状态及公共响应的 Servlet 响应。
     * @param status 本次外部 HTTP 响应或错误映射使用的状态码。
     * @param code 对外使用的稳定业务错误分类。
     * @param message 对外展示的业务错误或状态说明。
     */
    private static void writeError(
            ObjectMapper json,
            HttpServletResponse response,
            int status,
            String code,
            String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        json.writeValue(response.getOutputStream(), Results.failure(code, message));
    }
}
