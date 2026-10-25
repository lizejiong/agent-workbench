package com.lzj.rag.backend.security;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeBaseSecurityConfigurationTest {
    private final KnowledgeBaseSecurityConfiguration configuration = new KnowledgeBaseSecurityConfiguration();

    @Test void unsafePasswordLengthsFailClosedWithoutExposingSecrets() {
        for (String password : List.of("too-short", "密".repeat(25))) {
            var properties = new KnowledgeBaseSecurityProperties(List.of(account("alice", password)));
            assertThatThrownBy(() -> configuration.knowledgeBaseUsers(properties))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("KB account passwords must contain 16 to 72 UTF-8 bytes");
        }
    }

    @Test void caseInsensitiveDuplicateUsernamesFailClosed() {
        var properties = new KnowledgeBaseSecurityProperties(List.of(
                account("Alice", "a-safe-test-password"), account("alice", "another-test-password")));
        assertThatThrownBy(() -> configuration.knowledgeBaseUsers(properties))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("unique");
    }

    @Test void credentialLookupsRetainTrustedScopeAndCannotEraseStoredCredentials() {
        var properties = new KnowledgeBaseSecurityProperties(List.of(account("Alice", "a-safe-test-password")));
        var users = configuration.knowledgeBaseUsers(properties);
        var principal = (KnowledgeBasePrincipal) users.loadUserByUsername("alice");
        assertThat(principal.tenantId()).isEqualTo("tenant-a");
        assertThat(principal.userId()).isEqualTo("owner-a");
        assertThat(principal.getPassword()).startsWith("{bcrypt}").doesNotContain("a-safe-test-password");
        principal.eraseCredentials();
        assertThat(users.loadUserByUsername("ALICE").getPassword()).isNotBlank();
    }

    private static KnowledgeBaseSecurityProperties.Account account(String username, String password) {
        return new KnowledgeBaseSecurityProperties.Account(username, password, "tenant-a", "owner-a");
    }
}
