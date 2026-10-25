package com.lzj.rag.backend.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

@Validated
@ConfigurationProperties("kb.security")
public record KnowledgeBaseSecurityProperties(@NotEmpty List<@Valid Account> accounts) {
    public record Account(
            @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{0,127}") @NotBlank String username,
            @NotBlank String password,
            @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:-]{0,127}") @NotBlank String tenantId,
            @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:-]{0,127}") @NotBlank String userId, boolean operator) {
        @org.springframework.boot.context.properties.bind.ConstructorBinding
        public Account { }
        public Account(String username,String password,String tenantId,String userId){this(username,password,tenantId,userId,false);}
        @Override public String toString() {
            return "Account[username=" + username + ", password=[REDACTED]]";
        }
    }
}
