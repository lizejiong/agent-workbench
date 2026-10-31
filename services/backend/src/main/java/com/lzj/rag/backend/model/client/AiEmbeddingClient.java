package com.lzj.rag.backend.model.client;

import com.fasterxml.jackson.databind.*;
import com.lzj.rag.backend.knowledgebase.exception.KnowledgeBaseException;
import com.lzj.rag.backend.model.config.AiClientProperties;
import com.lzj.rag.backend.model.service.EmbeddingClient;
import com.lzj.rag.backend.model.service.bo.EmbeddingBindingBO;
import com.lzj.rag.backend.model.service.bo.EmbeddingResultBO;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** 调用 AI 服务并校验嵌入身份、有序向量和响应大小，同时复用受限 HTTP 传输。 */
@Component
@EnableConfigurationProperties(AiClientProperties.class)
public class AiEmbeddingClient implements EmbeddingClient {

    /** AI 服务连接、调用预算及响应大小限制。 */
    private final AiClientProperties config;

    /** 用于业务请求、持久化快照与元数据转换的 JSON 映射器。 */
    private final ObjectMapper json;

    /** 限制当前业务并发执行数量的许可集合。 */
    private final Semaphore permits;

    /** 配置了连接预算的 JDK HTTP 客户端。 */
    private final HttpClient client;

    /**
     * 初始化当前组件，保存业务依赖及本次使用的配置。
     *
     * @param config AI 服务连接、调用预算及响应大小限制。
     * @param json 用于业务请求、持久化快照与元数据转换的 JSON 映射器。
     */
    public AiEmbeddingClient(AiClientProperties config, ObjectMapper json) {
        this.config = config;
        this.json = json;
        permits = new Semaphore(config.concurrency());
        // uri：当前 XML 命名空间或外部请求 URI。
        URI uri = URI.create(config.baseUrl());
        if (!Set.of("http", "https").contains(uri.getScheme())
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null)
            throw new IllegalArgumentException("Invalid AI service URL");
        if (config.connectTimeout().isZero()
                || config.connectTimeout().isNegative()
                || config.callTimeout().isZero()
                || config.callTimeout().isNegative())
            throw new IllegalArgumentException("AI timeouts must be positive");
        client =
                HttpClient.newBuilder()
                        .connectTimeout(config.connectTimeout())
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
    }

    /**
     * 发送绑定模型的整组文本，校验返回身份、输入索引、维度及向量数值。
     *
     * @param binding 本次业务固定使用的嵌入模型身份与配置快照。
     * @param texts 本次需要计算嵌入的有序文本集合。
     * @param requestId 请求跟踪标识或业务幂等键，按当前接口约定使用。
     * @param deadline 本阶段或本次请求必须完成的截止时间。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @Override
    public EmbeddingResultBO embed(
            EmbeddingBindingBO binding, List<String> texts, String requestId, Instant deadline) {
        if (texts.isEmpty()) throw invalid();
        // descriptor：AI 返回的嵌入模型身份描述节点。
        JsonNode descriptor =
                call(
                        "GET",
                        "/ai/embedding-models/"
                                + binding.modelId()
                                + "?dimensions="
                                + binding.dimension(),
                        null,
                        requestId,
                        deadline);
        if (!descriptor.path("active").isBoolean() || !descriptor.path("active").booleanValue())
            throw failure(503, "KB_MODEL_UNAVAILABLE", "Bound embedding model is unavailable");
        identity(descriptor, binding);
        if (!"embed-v1".equals(descriptor.path("protocolVersion").asText())
                || !descriptor.path("batchSize").isIntegralNumber()
                || descriptor.path("batchSize").asInt() < 1) throw invalid();
        // revision：当前同步数据或配置使用的修订标识。
        String revision = descriptor.path("configRevision").asText();
        if (!revision.matches("[0-9a-f]{64}")) throw invalid();
        if (binding.expectedAiRevision() != null && !binding.expectedAiRevision().equals(revision))
            throw changed();
        // batchLimit：单批读取或处理记录的数量上限。
        int batchLimit = Math.min(config.batchSize(), descriptor.path("batchSize").asInt());
        // vectors：当前处理的有序向量集合。
        var vectors = new ArrayList<float[]>();
        // start：当前片段或表格分组的起始位置。
        int start = 0;
        while (start < texts.size()) {
            // end：当前范围的结束位置或局部阶段截止点。
            int end = start;
            // body：需要发送或当前接收的 HTTP 正文。
            // 批次预算使用编码后的请求字节数，包含 JSON 转义及模型身份字段。
            Map<String, Object> body;
            do {
                end++;
                body =
                        Map.of(
                                "texts",
                                texts.subList(start, end),
                                "model",
                                binding.modelId(),
                                "dimensions",
                                binding.dimension(),
                                "expectedConfigRevision",
                                revision);
                if (bytes(body).length > config.maxBatchBytes()) {
                    end--;
                    if (end == start)
                        throw failure(
                                422,
                                "KB_EMBEDDING_INPUT_TOO_LARGE",
                                "Single embedding input exceeds configured request byte budget");
                    break;
                }
            } while (end < texts.size() && end - start < batchLimit);
            body =
                    Map.of(
                            "texts",
                            texts.subList(start, end),
                            "model",
                            binding.modelId(),
                            "dimensions",
                            binding.dimension(),
                            "expectedConfigRevision",
                            revision);
            // response：当前 HTTP 响应或外部服务返回结果。
            var response = call("POST", "/ai/embed", body, requestId, deadline);
            if (!binding.modelId().equals(response.path("model").asText())
                    || !binding.provider().equals(response.path("provider").asText())
                    || !binding.embeddingSpace().equals(response.path("embeddingSpace").asText())
                    || !revision.equals(response.path("configRevision").asText())
                    || !response.path("dimensions").isIntegralNumber()
                    || response.path("dimensions").asInt() != binding.dimension()) throw changed();
            // values：本次处理的参数值或指标快照集合。
            var values = response.path("embeddings");
            // count：当前累计数量或本次流读取的有效字节数。
            int count = end - start;
            if (!values.isArray() || values.size() != count) throw invalid();
            // ordered：按原始输入位置恢复顺序的整组向量。
            float[][] ordered = new float[count][];
            // entry：当前处理的键值项。
            for (var entry : values) {
                // index：当前元素位置或关键词索引名称。
                var index = entry.path("index");
                if (!index.isIntegralNumber() || !index.canConvertToInt()) throw invalid();
                // n：AI 返回的向量原始输入位置，用于恢复顺序并检查重复。
                int n = index.intValue();
                if (n < 0
                        || n >= count
                        || ordered[n] != null
                        || !entry.path("values").isArray()
                        || entry.path("values").size() != binding.dimension()) throw invalid();
                // vector：当前查询或切片对应的浮点向量。
                float[] vector = new float[binding.dimension()];
                // nonzero：当前向量是否包含至少一个非零元素。
                boolean nonzero = false;
                // i：当前输入向量或向量维度的位置索引。
                for (int i = 0; i < vector.length; i++) {
                    // value：当前待校验、转换或写入的值。
                    var value = entry.path("values").get(i);
                    if (!value.isNumber() || !Double.isFinite(value.doubleValue())) throw invalid();
                    vector[i] = (float) value.doubleValue();
                    if (!Float.isFinite(vector[i])) throw invalid();
                    nonzero |= vector[i] != 0;
                }
                if (!nonzero) throw invalid();
                ordered[n] = vector;
            }
            vectors.addAll(Arrays.asList(ordered));
            start = end;
        }
        return new EmbeddingResultBO(List.copyOf(vectors), revision);
    }

    /**
     * 复核 AI 返回的模型、维度、向量空间及配置修订与固定绑定相符。
     *
     * @param descriptor AI 返回的嵌入模型身份描述节点。
     * @param binding 本次业务固定使用的嵌入模型身份与配置快照。
     */
    private void identity(JsonNode descriptor, EmbeddingBindingBO binding) {
        if (!binding.modelId().equals(descriptor.path("modelId").asText())
                || !binding.provider().equals(descriptor.path("provider").asText())
                || !binding.modelName().equals(descriptor.path("modelName").asText())
                || !binding.embeddingSpace().equals(descriptor.path("embeddingSpace").asText())
                || !binding.configVersion().equals(descriptor.path("configVersion").asText())
                || !descriptor.path("dimensions").isIntegralNumber()
                || descriptor.path("dimensions").asInt() != binding.dimension()) throw changed();
    }

    /**
     * 在共同 HTTP 限制下调用视觉模型接口。
     *
     * @param body 需要发送或当前接收的 HTTP 正文。
     * @param requestId 请求跟踪标识或业务幂等键，按当前接口约定使用。
     * @param deadline 本阶段或本次请求必须完成的截止时间。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public JsonNode visionCall(Map<String, Object> body, String requestId, Instant deadline) {
        return call("POST", "/ai/vision", body, requestId, deadline);
    }

    /**
     * 在共同 HTTP 限制下调用重排接口。
     *
     * @param body 需要发送或当前接收的 HTTP 正文。
     * @param requestId 请求跟踪标识或业务幂等键，按当前接口约定使用。
     * @param deadline 本阶段或本次请求必须完成的截止时间。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public JsonNode rerankCall(Map<String, Object> body, String requestId, Instant deadline) {
        return call("POST", "/ai/rerank", body, requestId, deadline);
    }

    public JsonNode chatCall(Map<String, Object> body, String requestId, Instant deadline) {
        return call("POST", "/ai/chat", body, requestId, deadline);
    }

    /**
     * 在调用截止时间和响应字节限制内请求 AI 服务，并归一化响应或错误。
     *
     * @param method 外部 HTTP 请求方法。
     * @param path 当前需要读取、写入或清理的文件路径。
     * @param body 需要发送或当前接收的 HTTP 正文。
     * @param requestId 请求跟踪标识或业务幂等键，按当前接口约定使用。
     * @param deadline 本阶段或本次请求必须完成的截止时间。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private JsonNode call(
            String method, String path, Object body, String requestId, Instant deadline) {
        // remaining：当前操作剩余的时间或容量预算。
        long remaining = Duration.between(Instant.now(), deadline).toMillis();
        if (remaining <= 0) throw timeout();
        // acquired：是否已经取得本次操作所需并发许可。
        boolean acquired = false;
        try {
            acquired = permits.tryAcquire(remaining, TimeUnit.MILLISECONDS);
            if (!acquired) throw timeout();
            remaining = Duration.between(Instant.now(), deadline).toMillis();
            if (remaining <= 0) throw timeout();
            // timeout：当前阶段允许使用的局部时间预算。
            long timeout = Math.min(remaining, config.callTimeout().toMillis());
            // request：当前入口接收的请求参数。
            var request =
                    HttpRequest.newBuilder(
                                    URI.create(config.baseUrl().replaceAll("/+$", "") + path))
                            .timeout(Duration.ofMillis(timeout))
                            .header("X-Request-Id", requestId)
                            .header("Content-Type", "application/json")
                            .method(
                                    method,
                                    body == null
                                            ? HttpRequest.BodyPublishers.noBody()
                                            : HttpRequest.BodyPublishers.ofByteArray(bytes(body)));
            if (config.token() != null && !config.token().isBlank())
                request.header("Authorization", "Bearer " + config.token());
            // info：HTTP 响应状态和头信息，用于选择受字节上限约束的正文订阅器。
            // future：本次异步操作对应的可等待、可取消结果。
            var future =
                    client.sendAsync(
                            request.build(), info -> new LimitedBody(config.maxResponseBytes()));
            // response：当前 HTTP 响应或外部服务返回结果。
            HttpResponse<byte[]> response;
            try {
                response = future.get(timeout, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                // e：本次捕获或匹配的异常，用于保留业务分类及失败原因。
                future.cancel(true);
                throw timeout();
            } catch (InterruptedException e) {
                // e：本次捕获或匹配的异常，用于保留业务分类及失败原因。
                future.cancel(true);
                throw e;
            }
            // status：本次外部 HTTP 响应或错误映射使用的状态码。
            int status = response.statusCode();
            // errorCode：可供调用方和运维识别的稳定错误码。
            String errorCode = "";
            if (status < 200 || status >= 300) {
                try {
                    var error = json.readTree(response.body());
                    if (error != null) errorCode = error.path("code").asText();
                } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
                    // 上游错误正文不是 JSON 时，继续根据 HTTP 状态归类失败。
                }
            }
            if (status == 409) throw changed();
            if (status == 408 || status == 504) throw timeout();
            if (status == 429 || errorCode.equals("RATE_LIMIT"))
                throw failure(
                        503,
                        "KB_MODEL_RATE_LIMITED",
                        "Bound model rate limit reached; retry with backoff");
            if (errorCode.equals("INVALID_RESPONSE")) throw invalid();
            if (errorCode.equals("AUTH"))
                throw failure(
                        502,
                        "KB_MODEL_REQUEST_REJECTED",
                        "Check the bound model credentials in the AI service");
            if (status == 400
                    || status == 401
                    || status == 403
                    || status == 404
                    || status == 415
                    || status == 422)
                throw failure(
                        502,
                        "KB_MODEL_REQUEST_REJECTED",
                        "Bound model request was rejected; check model configuration");
            if (status < 200 || status >= 300)
                throw failure(
                        503,
                        "KB_MODEL_UNAVAILABLE",
                        "Bound model request failed; no fallback was attempted");
            // data：当前表格数据或待处理字节内容。
            byte[] data = response.body();
            if (data.length > config.maxResponseBytes()) throw invalid();
            // root：JSON 响应根节点。
            JsonNode root = json.readTree(data);
            if (root == null
                    || !"0".equals(root.path("code").asText())
                    || !root.path("data").isObject()) throw invalid();
            return root.path("data");
        } catch (KnowledgeBaseException e) {
            // e：本次捕获或匹配的异常，用于保留业务分类及失败原因。
            throw e;
        } catch (InterruptedException e) {
            // e：本次捕获或匹配的异常，用于保留业务分类及失败原因。
            Thread.currentThread().interrupt();
            throw timeout();
        } catch (ExecutionException e) {
            // e：本次捕获或匹配的异常，用于保留业务分类及失败原因。
            if (e.getCause() instanceof java.net.http.HttpTimeoutException) throw timeout();
            if (e.getCause() instanceof IllegalStateException) throw invalid();
            throw failure(503, "KB_MODEL_UNAVAILABLE", "Embedding service connection failed");
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // e：本次捕获或匹配的异常，用于保留业务分类及失败原因。
            throw invalid();
        } catch (java.io.IOException e) {
            // e：本次捕获或匹配的异常，用于保留业务分类及失败原因。
            throw invalid();
        } finally {
            if (acquired) permits.release();
        }
    }

    /** 限制累计响应字节数的 HTTP 订阅器，超限即取消接收。 */
    public static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {

        /** 当前校验允许的最大长度或数值。 */
        private final int max;

        /** 当前结果集合或文件输出流。 */
        private final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();

        /** 当前异步操作的可等待结果，超时或失去执行权时用于取消任务。 */
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();

        /** 响应流的上游订阅，用于请求数据或在超限时取消。 */
        private java.util.concurrent.Flow.Subscription subscription;

        /**
         * 初始化当前组件，保存业务依赖及本次使用的配置。
         *
         * @param max 当前校验允许的最大长度或数值。
         */
        public LimitedBody(int max) {
            this.max = max;
        }

        /**
         * 返回响应字节的完成阶段，达到上限或接收失败时异常完成。
         *
         * @return 当前业务处理结果；失败时通过异常报告。
         */
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        /**
         * 保存上游订阅并请求首批响应数据。
         *
         * @param subscription 响应流的上游订阅，用于请求数据或在超限时取消。
         */
        public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }

        /**
         * 逐块累计响应字节，超出限制时取消订阅并结束接收。
         *
         * @param items 本次响应回调送达的字节缓冲块集合。
         */
        public void onNext(List<ByteBuffer> items) {
            // item：当前处理的集合元素。
            for (var item : items) {
                if ((long) out.size() + item.remaining() > max) {
                    subscription.cancel();
                    result.completeExceptionally(
                            new IllegalStateException("AI response exceeded limit"));
                    return;
                }
                // bytes：已序列化或读取的原始字节内容。
                byte[] bytes = new byte[item.remaining()];
                item.get(bytes);
                out.writeBytes(bytes);
            }
            subscription.request(1);
        }

        /**
         * 以传输错误结束响应读取。
         *
         * @param error 本次处理捕获的异常，用于归类失败原因。
         */
        public void onError(Throwable error) {
            result.completeExceptionally(error);
        }

        /** 响应读取结束时提交累计字节。 */
        public void onComplete() {
            result.complete(out.toByteArray());
        }
    }

    /**
     * 将请求对象序列化为 UTF-8 JSON 字节。
     *
     * @param value 当前待校验、转换或写入的值。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private byte[] bytes(Object value) {
        try {
            return json.writeValueAsBytes(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // e：本次捕获或匹配的异常，用于保留业务分类及失败原因。
            throw invalid();
        }
    }

    /**
     * 创建模型配置身份发生变化的异常。
     *
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private static KnowledgeBaseException changed() {
        return failure(
                409,
                "KB_MODEL_CONFIG_CHANGED",
                "Bound embedding identity or configuration changed; rebuild using a new knowledge base");
    }

    /**
     * 创建输入或协议校验失败的业务异常。
     *
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private static KnowledgeBaseException invalid() {
        return failure(
                502, "KB_MODEL_INVALID_RESPONSE", "Embedding service returned an invalid response");
    }

    /**
     * 创建调用预算耗尽的超时异常。
     *
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private static KnowledgeBaseException timeout() {
        return failure(504, "KB_MODEL_TIMEOUT", "Embedding call or ingestion deadline exceeded");
    }

    /**
     * 按给定 HTTP 状态和错误码创建业务异常。
     *
     * @param status 本次外部 HTTP 响应或错误映射使用的状态码。
     * @param code 对外使用的稳定业务错误分类。
     * @param message 对外展示的业务错误或状态说明。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private static KnowledgeBaseException failure(int status, String code, String message) {
        return new KnowledgeBaseException(status, code, message);
    }
}
