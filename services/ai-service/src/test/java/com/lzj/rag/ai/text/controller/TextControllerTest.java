package com.lzj.rag.ai.text.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(properties = "ai.enabled=false", webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class TextControllerTest {
    @Autowired WebTestClient client;

    @Test
    void textToolsRemainAvailableWhenModelsAreDisabled() {
        post("/ai/utils/estimate-tokens", "{\"text\":\"你好abcd\"}")
                .expectStatus().isOk().expectBody()
                .jsonPath("$.code").isEqualTo("0")
                .jsonPath("$.requestId").isEqualTo("text-test")
                .jsonPath("$.data.tokens").isEqualTo(3)
                .jsonPath("$.data.estimated").isEqualTo(true);
        post("/ai/utils/strip-code-fence", "{\"text\":\"```json\\n{}\\n```\"}")
                .expectStatus().isOk().expectBody().jsonPath("$.data.text").isEqualTo("{}");
        post("/ai/utils/strip-code-fence", "{\"text\":\"prose\\n```\\nx\\n```\"}")
                .expectStatus().isOk().expectBody().jsonPath("$.data.text").isEqualTo("prose\n```\nx\n```");
        post("/ai/chat", "{\"text\":\"hello\"}").expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.code").isEqualTo("UNAVAILABLE");
    }

    @Test
    void missingTextUsesTheSharedJsonErrorContract() {
        for (String path : new String[]{"/ai/utils/estimate-tokens", "/ai/utils/strip-code-fence"}) {
            post(path, "{}").expectStatus().isBadRequest().expectBody()
                    .jsonPath("$.code").isEqualTo("INVALID_INPUT")
                    .jsonPath("$.requestId").isEqualTo("text-test");
        }
    }

    private WebTestClient.ResponseSpec post(String path, String body) {
        return client.post().uri(path).header("X-Request-Id", "text-test")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange()
                .expectHeader().valueEquals("X-Request-Id", "text-test")
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON);
    }
}
