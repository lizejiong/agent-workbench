package com.lzj.rag.ai;

import com.fasterxml.jackson.databind.*;
import com.lzj.rag.ai.api.ApiTypes.*;
import com.lzj.rag.ai.config.ModelRegistry;
import com.lzj.rag.ai.resilience.RequestGate;
import com.lzj.rag.ai.service.AiException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.*;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.*;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

/** Critical behavior through real local HTTP; no vendor keys or paid calls. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AiServiceTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final Map<String, JsonNode> bodies = new ConcurrentHashMap<>();
    static final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
    static final DisposableServer upstream = HttpServer.create().host("127.0.0.1").port(0).handle(AiServiceTest::reply).bindNow();
    @LocalServerPort int port;
    @Autowired ModelRegistry registry;
    WebTestClient client;
    @DynamicPropertySource static void config(DynamicPropertyRegistry p) {
        String url = "http://127.0.0.1:" + upstream.port();
        for (String provider : List.of("bailian", "deepseek", "siliconflow")) {
            p.add("ai.providers." + provider + ".base-url", () -> url);
            p.add("ai.providers." + provider + ".api-key", () -> "local-test-key");
            p.add("ai.providers." + provider + ".rerank-url", () -> url + "/rerank");
        }
        p.add("ai.models.qwen-embed.dimensions", () -> 2);
        p.add("ai.models.qwen-embed.allowed-dimensions[0]", () -> 2);
        p.add("ai.models.qwen-embed.batch-size", () -> 2);
        p.add("ai.models.silicon-embed.dimensions", () -> 2);
        p.add("ai.models.silicon-embed.batch-size", () -> 2);
        p.add("ai.models.silicon-embed.embedding-space", () -> "bailian-text-embedding-v4");
        p.add("ai.embedding.fallbacks[0]", () -> "silicon-embed");
        p.add("ai.limits.first-token-timeout", () -> "200ms");
        p.add("ai.limits.stream-idle-timeout", () -> "500ms");
        p.add("ai.limits.total-timeout", () -> "3s");
        p.add("ai.limits.circuit-failures", () -> 100);
        p.add("ai.limits.circuit-cooldown", () -> "30ms");
    }
    @BeforeEach void setup() {
        bodies.clear(); calls.clear();
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).responseTimeout(Duration.ofSeconds(6)).build();
    }
    @AfterAll static void stop() { upstream.disposeNow(); }

    @Test void fallbackAndNativeThinkingToolsKeepUnknownUsage() throws Exception {
        String request = """
            {"text":"fallback","thinking":true,"tools":[{"name":"lookup","parameters":{"type":"object"}}]}
            """;
        JsonNode result = post("/ai/chat", request, 200);
        assertThat(result.at("/data/model").asText()).isEqualTo("deepseek-chat");
        assertThat(result.at("/data/metrics/attempts").asInt()).isEqualTo(2);
        assertThat(result.at("/data/usage").isNull()).isTrue();
        assertThat(bodies.get("qwen-plus").path("enable_thinking").asBoolean()).isTrue();
        assertThat(bodies.get("deepseek-flash").at("/thinking/type").asText()).isEqualTo("enabled");
        String continuation = """
            {"preferredModel":"deepseek-chat","messages":[
             {"role":"assistant","reasoning":"keep","toolCalls":[{"id":"t1","name":"lookup","arguments":"{}"}]},
             {"role":"tool","toolCallId":"t1","content":"result"}]}
            """;
        post("/ai/chat", continuation, 200);
        JsonNode sent = bodies.get("deepseek-flash");
        assertThat(sent.at("/messages/0/reasoning_content").asText()).isEqualTo("keep");
        assertThat(sent.at("/messages/1/tool_call_id").asText()).isEqualTo("t1");
        assertThat(sent.at("/thinking/type").asText()).isEqualTo("disabled");
    }

    @Test void streamIgnoresEmptyProbeThenFallsBackAndKeepsToolIndicesAndTailUsage() {
        String events = stream("{\"text\":\"probe\"}");
        assertThat(events).contains("event:start", "event:reasoning", "event:content", "event:tool", "event:usage", "event:done");
        assertThat(events).contains("\"model\":\"deepseek-chat\"", "\"index\":0", "\"inputTokens\":3", "\"text\":\" \"");
        assertThat(events).doesNotContain("\"model\":\"qwen-chat\"", "event:error");
        assertThat(calls.get("qwen-plus").get()).isEqualTo(1);
        assertThat(calls.get("deepseek-flash").get()).isEqualTo(1);
    }

    @Test void streamTruncatedAfterVisibleContentNeverChangesModel() {
        String events = stream("{\"text\":\"truncated\"}");
        assertThat(events).contains("event:start", "event:content", "event:error", "INVALID_RESPONSE").doesNotContain("event:done");
        assertThat(calls).doesNotContainKey("deepseek-flash");
    }

    @Test void embeddingRerunsEntireGroupAndPreservesOrderAndVectorIdentity() throws Exception {
        JsonNode result = post("/ai/embed", "{\"texts\":[\"1\",\"2\",\"3\"],\"dimensions\":2}", 200);
        assertThat(result.at("/data/model").asText()).isEqualTo("silicon-embed");
        assertThat(result.at("/data/embeddings/0/values/0").asInt()).isEqualTo(1);
        assertThat(result.at("/data/embeddings/2/index").asInt()).isEqualTo(2);
        assertThat(result.at("/data/usage").isNull()).isTrue();
        assertThat(calls.get("text-embedding-v4").get()).isEqualTo(2);
        assertThat(calls.get("BAAI/bge-m3").get()).isEqualTo(2);
        assertThat(bodies.get("BAAI/bge-m3").has("dimensions")).isFalse();
        post("/ai/embed", "{\"text\":\"1\",\"dimensions\":17}", 400);
    }

    @Test void rerankUsesNativeMappingsAndStableOrder() throws Exception {
        String request = "{\"query\":\"q\",\"documents\":[{\"id\":\"a\",\"text\":\"A\"},{\"id\":\"b\",\"text\":\"B\"}],\"topN\":2}";
        JsonNode result = post("/ai/rerank", request, 200);
        assertThat(result.at("/data/results/0/chunkId").asText()).isEqualTo("a");
        assertThat(bodies.get("qwen3-rerank").at("/input/query").asText()).isEqualTo("q");
        assertThat(bodies.get("qwen3-rerank").at("/parameters/return_documents").asBoolean()).isFalse();
        post("/ai/rerank", request.replace("\"query\"", "\"model\":\"silicon-rerank\",\"query\""), 200);
        assertThat(bodies.get("BAAI/bge-reranker-v2-m3").path("query").asText()).isEqualTo("q");
    }

    @Test void visionUsesContentPartsAndRequiresImages() throws Exception {
        post("/ai/vision", "{\"text\":\"image\"}", 400);
        post("/ai/vision", "{\"messages\":[{\"role\":\"user\",\"content\":\"image\",\"images\":[{\"base64\":\"YQ==\",\"mediaType\":\"image/png\"}]}]}", 200);
        assertThat(bodies.get("qwen3-vl-plus").at("/messages/0/content/1/image_url/url").asText()).isEqualTo("data:image/png;base64,YQ==");
    }

    @Test void malformedRequestsAreJsonAndJsonModeDoesNotStripFences() throws Exception {
        post("/ai/chat/stream", "{\"text\":\"x\",\"messages\":[]}", 400);
        JsonNode failure = post("/ai/chat", "{\"text\":\"fenced\",\"json\":true}", 502);
        assertThat(failure.path("code").asText()).isEqualTo("INVALID_RESPONSE");
        assertThat(post("/ai/utils/estimate-tokens", "{\"text\":\"你好abcd\"}", 200).at("/data/estimated").asBoolean()).isTrue();
        assertThat(post("/ai/utils/strip-code-fence", "{\"text\":\"```json\\n{}\\n```\"}", 200).at("/data/text").asText()).isEqualTo("{}");
        assertThat(post("/ai/utils/strip-code-fence", "{\"text\":\"prose\\n```\\nx\\n```\"}", 200).at("/data/text").asText()).startsWith("prose");
    }

    @Test void staleSuccessCannotCloseCircuitAndCancelledProbeReleasesLease() throws Exception {
        RequestGate gate = new RequestGate(registry);
        var stale = gate.acquire("qwen-chat");
        for (int i = 0; i < registry.config().limits().circuitFailures(); i++) gate.acquire("qwen-chat").failure(true);
        stale.success();
        assertThatThrownBy(() -> gate.acquire("qwen-chat")).isInstanceOf(AiException.class);
        Thread.sleep(45);
        var probe = gate.acquire("qwen-chat");
        assertThatThrownBy(() -> gate.acquire("qwen-chat")).isInstanceOf(AiException.class);
        probe.close();
        gate.acquire("qwen-chat").success();
        gate.acquire("qwen-chat").close();
    }
    JsonNode post(String path, String body, int status) throws Exception {
        byte[] bytes = client.post().uri(path).header("X-Request-Id", "test-id").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body).exchange().expectStatus().isEqualTo(status).expectHeader().valueEquals("X-Request-Id", "test-id")
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON).expectBody().returnResult().getResponseBody();
        JsonNode result = JSON.readTree(bytes); assertThat(result.path("requestId").asText()).isEqualTo("test-id"); return result;
    }
    String stream(String body) {
        return client.post().uri("/ai/chat/stream").contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange().expectStatus().isOk().expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .expectBody(String.class).returnResult().getResponseBody();
    }
    static Mono<Void> reply(HttpServerRequest req, HttpServerResponse res) {
        return req.receive().aggregate().asString().flatMap(raw -> {
            try {
                JsonNode body = JSON.readTree(raw); String model = body.path("model").asText();
                bodies.put(model, body); int count = calls.computeIfAbsent(model, m -> new AtomicInteger()).incrementAndGet();
                String text = body.at("/messages/0/content").asText();
                if (model.equals("qwen-plus") && text.equals("fallback")) return res.status(500).send().then();
                if (req.uri().equals("/embeddings")) {
                    if (model.equals("text-embedding-v4") && count == 2) return res.status(500).send().then();
                    var root = JSON.createObjectNode(); var data = root.putArray("data");
                    for (int i = body.path("input").size() - 1; i >= 0; i--)
                        data.addObject().put("index", i).putArray("embedding").add(Integer.parseInt(body.path("input").get(i).asText())).add(0);
                    return res.header("Content-Type", "application/json").sendString(Mono.just(root.toString())).then();
                }
                if (req.uri().equals("/rerank")) {
                    String results = "\"results\":[{\"index\":1,\"relevance_score\":0.9},{\"index\":0,\"relevance_score\":0.9}]";
                    return res.header("Content-Type", "application/json").sendString(Mono.just(model.equals("qwen3-rerank") ? "{\"output\":{" + results + "}}" : "{" + results + "}")).then();
                }
                if (body.path("stream").asBoolean()) {
                    res.header("Content-Type", "text/event-stream");
                    if (model.equals("qwen-plus") && text.equals("probe"))
                        return res.sendString(Flux.interval(Duration.ofMillis(20)).map(i -> "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"\"}}]}\n\n")).then();
                    if (text.equals("truncated")) return res.sendString(Mono.just(sse("{\"content\":\"visible\"}"))).then();
                    return res.sendString(Flux.just(sse("{\"reasoning_content\":\"r\"}"), sse("{\"content\":\" \"}"),
                            sse("{\"tool_calls\":[{\"index\":0,\"id\":\"t\",\"function\":{\"name\":\"lookup\",\"arguments\":\"{\"}}]}"),
                            sse("{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"}\"}}]}"),
                            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n",
                            "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2,\"total_tokens\":5}}\n\n", "data: [DONE]\n\n")).then();
                }
                String content = text.equals("fenced") ? "```json\n{}\n```" : "ok";
                String value = JSON.writeValueAsString(Map.of("choices", List.of(Map.of("message", Map.of("content", content), "finish_reason", "stop"))));
                return res.header("Content-Type", "application/json").sendString(Mono.just(value)).then();
            } catch (Exception e) { return Mono.error(e); }
        });
    }
    static String sse(String delta) { return "data: {\"choices\":[{\"index\":0,\"delta\":" + delta + "}]}\n\n"; }
}
