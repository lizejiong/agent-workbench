package com.lzj.rag.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Runs unchanged against a disposable real PostgreSQL database, never H2 or mocked mappers. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
abstract class KnowledgeBaseContract {
    static final String API = "/api/v1/knowledge-bases";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;

    @BeforeEach void cleanOwnedFixture() {
        TestDatabaseCleanup.clear(jdbc);
    }

    @Test void migrationIsValidAndCanBeAppliedAgainWithoutLosingData() throws Exception {
        JsonNode created = create("Persistent");
        flyway.validate();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        request(get(API + "/" + created.path("id").asText()), "alice", 200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kb_flyway_schema_history WHERE success", Integer.class))
                .isEqualTo(8);
    }

    @Test void fullLifecyclePersistsVersionAndTombstone() throws Exception {
        JsonNode created = create("  Product handbook  ");
        String id = created.path("id").asText();
        assertThat(created.path("name").asText()).isEqualTo("Product handbook");
        assertThat(created.path("description").asText()).isEmpty();
        assertThat(created.path("version").asLong()).isEqualTo(1);
        assertThat(created.path("createdAt").asText()).isNotEmpty();
        var stored = request(get(API + "/" + id), "alice", 200).path("data");
        assertThat(stored.path("id")).isEqualTo(created.path("id"));
        assertThat(stored.path("createdAt")).isEqualTo(created.path("createdAt"));
        JsonNode updated = request(put(API + "/" + id).contentType(APPLICATION_JSON)
                .content("{\"name\":\"Updated\",\"description\":\"中文说明\",\"version\":1}"), "alice", 200).path("data");
        assertThat(updated.path("version").asLong()).isEqualTo(2);
        assertThat(updated.path("description").asText()).isEqualTo("中文说明");
        request(delete(API + "/" + id).param("version", "2"), "alice", 200);
        request(get(API + "/" + id), "alice", 404);
        request(put(API + "/" + id).contentType(APPLICATION_JSON)
                .content("{\"name\":\"Resurrected\",\"version\":3}"), "alice", 404);
        request(delete(API + "/" + id).param("version", "3"), "alice", 404);
        assertThat(jdbc.queryForObject("SELECT version FROM kb_knowledge_base WHERE id = ?", Long.class, UUID.fromString(id)))
                .isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM kb_knowledge_base WHERE id = ?", Boolean.class,
                UUID.fromString(id))).isTrue();
        assertThat(request(get(API), "alice", 200).at("/data/total").asLong()).isZero();
    }

    @Test void otherOwnerAndOtherTenantCannotReadMutateOrListTheBase() throws Exception {
        String id = create("Private").path("id").asText();
        for (String user : List.of("bob", "carol")) {
            assertThat(request(get(API), user, 200).at("/data/total").asLong()).isZero();
            request(get(API + "/" + id), user, 404);
            request(put(API + "/" + id).contentType(APPLICATION_JSON)
                    .content("{\"name\":\"Stolen\",\"version\":1}"), user, 404);
            request(delete(API + "/" + id).param("version", "1"), user, 404);
        }
        assertThat(request(get(API + "/" + id), "alice", 200).at("/data/version").asLong()).isEqualTo(1);
    }

    @Test void callerCannotForgeTenantOrOwner() throws Exception {
        request(post(API).contentType(APPLICATION_JSON)
                .content("{\"name\":\"Forged\",\"tenantId\":\"tenant-b\",\"ownerId\":\"user-bob\"}"), "alice", 400);
        request(post(API).header("X-Tenant-Id", "tenant-b").header("X-User-Id", "user-bob")
                .contentType(APPLICATION_JSON).content("{\"name\":\"Bound identity\"}"), "alice", 201);
        assertThat(jdbc.queryForObject("SELECT tenant_id FROM kb_knowledge_base", String.class)).isEqualTo("tenant-a");
        assertThat(jdbc.queryForObject("SELECT owner_id FROM kb_knowledge_base", String.class)).isEqualTo("user-alice");
    }

    @Test void staleUpdateAndStaleDeleteFailWithoutChangingData() throws Exception {
        String id = create("Original").path("id").asText();
        request(put(API + "/" + id).contentType(APPLICATION_JSON)
                .content("{\"name\":\"Winner\",\"version\":1}"), "alice", 200);
        assertThat(request(put(API + "/" + id).contentType(APPLICATION_JSON)
                .content("{\"name\":\"Loser\",\"version\":1}"), "alice", 409).path("code").asText())
                .isEqualTo("KB_VERSION_CONFLICT");
        request(delete(API + "/" + id).param("version", "1"), "alice", 409);
        assertThat(request(get(API + "/" + id), "alice", 200).at("/data/name").asText()).isEqualTo("Winner");
    }

    @Test void concurrentUpdatesHaveExactlyOneWinner() throws Exception {
        String id = create("Concurrent").path("id").asText();
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var calls = new ArrayList<Callable<Integer>>();
            for (String name : List.of("Writer A", "Writer B")) {
                calls.add(() -> {
                    start.await();
                    return mvc.perform(put(API + "/" + id).with(httpBasic("alice", password("alice")))
                            .contentType(APPLICATION_JSON).content(json.writeValueAsString(
                                    java.util.Map.of("name", name, "version", 1))))
                            .andReturn().getResponse().getStatus();
                });
            }
            var first = executor.submit(calls.get(0));
            var second = executor.submit(calls.get(1));
            start.countDown();
            assertThat(List.of(first.get(15, java.util.concurrent.TimeUnit.SECONDS),
                    second.get(15, java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 409);
        } finally {
            executor.shutdownNow();
        }
        assertThat(request(get(API + "/" + id), "alice", 200).at("/data/version").asLong()).isEqualTo(2);
    }

    @Test void nameUniquenessIsAtomicScopedAndExcludesTombstones() throws Exception {
        String id = create("Handbook").path("id").asText();
        assertThat(request(post(API).contentType(APPLICATION_JSON).content("{\"name\":\"handbook\"}"), "alice", 409)
                .path("code").asText()).isEqualTo("KB_NAME_EXISTS");
        request(post(API).contentType(APPLICATION_JSON).content("{\"name\":\"handbook\"}"), "bob", 201);
        request(post(API).contentType(APPLICATION_JSON).content("{\"name\":\"handbook\"}"), "carol", 201);
        request(delete(API + "/" + id).param("version", "1"), "alice", 200);
        create("handbook");
    }

    @Test void duplicateRenameRollsBackAllChanges() throws Exception {
        create("Reserved");
        String id = create("Retained").path("id").asText();
        request(put(API + "/" + id).contentType(APPLICATION_JSON)
                .content("{\"name\":\"reserved\",\"description\":\"must rollback\",\"version\":1}"), "alice", 409);
        var retained = request(get(API + "/" + id), "alice", 200).path("data");
        assertThat(retained.path("name").asText()).isEqualTo("Retained");
        assertThat(retained.path("description").asText()).isEmpty();
        assertThat(retained.path("version").asLong()).isEqualTo(1);
    }

    @Test void paginationUsesStableOrderAndBounds() throws Exception {
        for (int i = 1; i <= 3; i++) create("Page " + i);
        JsonNode first = request(get(API).param("size", "2"), "alice", 200).path("data");
        JsonNode second = request(get(API).param("size", "2").param("current", "2"), "alice", 200).path("data");
        assertThat(first.path("total").asLong()).isEqualTo(3);
        assertThat(first.path("records").size()).isEqualTo(2);
        assertThat(second.path("records").size()).isEqualTo(1);
        assertThat(first.at("/records/0/name").asText()).isEqualTo("Page 3");
        assertThat(second.at("/records/0/name").asText()).isEqualTo("Page 1");
        request(get(API).param("size", "101"), "alice", 400);
        request(get(API).param("current", "0"), "alice", 400);
        request(get(API).param("current", "2147483648"), "alice", 400);
        request(get(API).param("size", "abc"), "alice", 400);
    }

    @Test void validationRejectsMalformedBlankOversizedUnknownAndCoercedInput() throws Exception {
        for (String body : List.of("{", "{}", "null", "{\"name\":\"   \"}", "{\"name\":\"　\"}",
                "{\"name\":123}", "{\"name\":\"Valid\",\"unexpected\":true}",
                json.writeValueAsString(java.util.Map.of("name", "null\u0000character")),
                json.writeValueAsString(java.util.Map.of("name", "Valid", "description", "null\u0000character")),
                json.writeValueAsString(java.util.Map.of("name", "x".repeat(129))),
                json.writeValueAsString(java.util.Map.of("name", "Valid", "description", "x".repeat(2001))))) {
            request(post(API).contentType(APPLICATION_JSON).content(body), "alice", 400);
        }
        request(get(API + "/not-a-uuid"), "alice", 400);
        String id = create("Valid").path("id").asText();
        request(put(API + "/" + id).contentType(APPLICATION_JSON).content("{\"name\":\"Valid\"}"), "alice", 400);
        request(put(API + "/" + id).contentType(APPLICATION_JSON)
                .content("{\"name\":\"Valid\",\"version\":0}"), "alice", 400);
        request(put(API + "/" + id).contentType(APPLICATION_JSON)
                .content("{\"name\":\"Valid\",\"version\":1.5}"), "alice", 400);
        request(delete(API + "/" + id), "alice", 400);
        request(delete(API + "/" + id).param("version", "-1"), "alice", 400);
    }

    @Test void authenticationAndRequestIdEnvelopeAreConsistent() throws Exception {
        mvc.perform(get(API).header("X-Request-Id", "kb-auth-1"))
                .andExpect(status().isUnauthorized()).andExpect(header().exists("WWW-Authenticate"))
                .andExpect(header().string("X-Request-Id", "kb-auth-1"))
                .andExpect(jsonPath("$.code").value("KB_UNAUTHENTICATED"))
                .andExpect(jsonPath("$.requestId").value("kb-auth-1"));
        mvc.perform(get(API).with(httpBasic("alice", "wrong"))).andExpect(status().isUnauthorized());
        mvc.perform(get(API).with(httpBasic("nobody", password("alice")))).andExpect(status().isUnauthorized());
        var result = mvc.perform(get(API).with(httpBasic("alice", password("alice")))
                .header("X-Request-Id", "x".repeat(129))).andExpect(status().isOk()).andReturn();
        String id = result.getResponse().getHeader("X-Request-Id");
        assertThat(id).hasSizeLessThanOrEqualTo(128);
        assertThat(json.readTree(result.getResponse().getContentAsByteArray()).path("requestId").asText()).isEqualTo(id);
        mvc.perform(get(API)).andExpect(status().isUnauthorized()); // no previous authentication/session survives
    }

    @Test void healthAndAuthenticatedOpenApiAreAvailable() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/openapi/knowledge-base.yaml")).andExpect(status().isUnauthorized());
        mvc.perform(get("/openapi/knowledge-base.yaml").with(httpBasic("alice", password("alice"))))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("openapi: 3.0.3")));
        request(get("/api/v1/not-found"), "alice", 404);
        request(patch(API), "alice", 405);
        request(post(API).contentType("text/plain").content("abc"), "alice", 415);
    }

    private JsonNode create(String name) throws Exception {
        var response = mvc.perform(post(API).with(httpBasic("alice", password("alice"))).contentType(APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("name", name))))
                .andExpect(status().isCreated()).andExpect(header().exists("Location")).andReturn();
        return json.readTree(response.getResponse().getContentAsByteArray()).path("data");
    }

    private JsonNode request(MockHttpServletRequestBuilder request, String user, int expectedStatus) throws Exception {
        var response = mvc.perform(request.with(httpBasic(user, password(user))))
                .andExpect(status().is(expectedStatus)).andExpect(jsonPath("$.requestId").isNotEmpty()).andReturn();
        var body = json.readTree(response.getResponse().getContentAsByteArray());
        assertThat(body.path("code").asText()).isNotEqualTo("");
        if (expectedStatus < 300) assertThat(body.path("code").asText()).isEqualTo("0");
        return body;
    }

    private static String password(String username) { return username + "-local-test-password"; }
}
