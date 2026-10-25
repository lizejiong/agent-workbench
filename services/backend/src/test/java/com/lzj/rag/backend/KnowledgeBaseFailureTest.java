package com.lzj.rag.backend;

import com.lzj.rag.backend.api.KnowledgeBaseErrors;
import com.lzj.rag.backend.knowledgebase.KnowledgeBaseController;
import com.lzj.rag.backend.knowledgebase.KnowledgeBaseService;
import com.lzj.rag.backend.security.KnowledgeBaseSecurityProperties;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class KnowledgeBaseFailureTest {
    @Test void databaseOutageReturnsRetryableStatusAndHidesInternalDetails() throws Exception {
        var service = mock(KnowledgeBaseService.class);
        when(service.create(any(), any())).thenThrow(new DataAccessResourceFailureException("password=secret db-host"));
        var mvc = MockMvcBuilders.standaloneSetup(new KnowledgeBaseController(service))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new KnowledgeBaseErrors()).build();
        mvc.perform(post("/api/v1/knowledge-bases").contentType("application/json").content("{\"name\":\"Test\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("KB_DATABASE_UNAVAILABLE"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret"))));
    }

    @Test void unexpectedFailureIsSanitized() throws Exception {
        var service = mock(KnowledgeBaseService.class);
        when(service.create(any(), any())).thenThrow(new IllegalStateException("secret internal details"));
        var mvc = MockMvcBuilders.standaloneSetup(new KnowledgeBaseController(service))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new KnowledgeBaseErrors()).build();
        mvc.perform(post("/api/v1/knowledge-bases").contentType("application/json").content("{\"name\":\"Test\"}"))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("KB_INTERNAL"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret"))));
    }

    @Test void secretsAreRedactedFromConfigurationToString() {
        var account = new KnowledgeBaseSecurityProperties.Account("alice", "sensitive-password", "tenant-a", "alice");
        assertThat(account.toString()).doesNotContain("sensitive-password");
    }
}
