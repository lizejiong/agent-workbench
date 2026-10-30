package com.lzj.rag.ai.infra.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.lzj.rag.ai.infra.provider.dto.*;
import com.lzj.rag.ai.infra.provider.dto.ProviderChatRequestDTO.MessageDTO;
import com.lzj.rag.ai.infra.provider.dto.ProviderEmbeddingsDTO.VectorDTO;
import com.lzj.rag.ai.infra.provider.dto.ProviderRankingDTO.ScoreDTO;
import com.lzj.rag.ai.infra.provider.enums.ProviderKind;
import com.lzj.rag.ai.infra.provider.exception.ModelInvocationException;
import io.netty.channel.ChannelOption;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.*;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.*;
import reactor.core.publisher.*;
import reactor.netty.http.client.HttpClient;

/**
 * 供应商协议客户端，负责认证、HTTP/SSE 传输与原生返回结构校验。
 */
public class ProviderClient {

    /**
     * 只记录请求标识、模型、分类和耗时的日志器。
     */
    private static final Logger log = LoggerFactory.getLogger(ProviderClient.class);

    /**
     * JSON 对象解析器与构造器。
     */
    private final ObjectMapper json;

    /**
     * 已配置连接超时和响应大小限制的响应式 HTTP 客户端。
     */
    private final WebClient http;

    /**
     * 创建带连接超时和响应大小限制的 HTTP 客户端。
     *
     * @param json JSON 对象解析器与构造器。
     * @param connectTimeout 建立供应商网络连接允许的最长时间。
     * @param maxResponseBytes 上游单个响应或 SSE 数据帧的最大缓冲字节数。
     */
    public ProviderClient(ObjectMapper json, java.time.Duration connectTimeout, int maxResponseBytes) {
        this.json = json;
        // c：WebClient 编解码配置器，用于限制响应缓冲大小。
        this.http = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(HttpClient.create()
                        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS,
                                (int) Math.min(Integer.MAX_VALUE, connectTimeout.toMillis()))))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(maxResponseBytes))
                .build();
    }

    /**
     * 发送一次非流式对话，校验消息与完成原因，并归一化工具调用和实际用量。
     *
     * @param target 本次调用所使用的供应商模型与连接快照。
     * @param request 已去除业务路由信息的供应商对话技术请求。
     * @param requestId 贯穿接口、业务调用、供应商请求与日志的关联 ID。
     * @return 供应商对话结果；无效响应通过错误信号返回。
     */
    public Mono<ProviderChatDTO> chat(ProviderModelDTO target, ProviderChatRequestDTO request, String requestId) {
        return post(target, "/chat/completions", chatBody(target, request, false), requestId).map(root -> {
            // root：供应商 JSON 响应或当前 SSE 帧的根节点。
            // 供应商返回的模型候选，当前仅处理第一个对话候选。
            JsonNode choice = root.path("choices").path(0);
            // 供应商第一条候选中的完整消息节点。
            JsonNode message = choice.path("message");
            if (!message.isObject() || !choice.path("finish_reason").isTextual())
                throw ModelInvocationException.invalid();
            // 当前消息中按顺序解析或组装的工具调用列表。
            List<ProviderToolCallDTO> calls = new ArrayList<>();
            // call：当前工具调用或工具增量，携带函数名、参数与关联 ID。
            for (JsonNode call : message.path("tool_calls")) {
                // 通过完整字段校验的归一化工具调用。
                ProviderToolCallDTO normalized = tool(call, calls.size());
                if (normalized.id() == null
                        || normalized.id().isEmpty()
                        || normalized.name() == null
                        || normalized.name().isEmpty()
                        || normalized.arguments() == null)
                    throw ModelInvocationException.invalid();
                calls.add(normalized);
            }
            // 模型返回的正文内容，与推理文本分开保存。
            String content = optionalText(message, "content");
            // 模型独立返回的推理内容，也可用于工具回合续接。
            String reasoning = optionalText(message, "reasoning_content");
            if ((content == null || content.isEmpty()) && (reasoning == null || reasoning.isEmpty()) && calls.isEmpty())
                throw ModelInvocationException.invalid();
            return new ProviderChatDTO(content, reasoning, calls, choice.path("finish_reason").asText(), usage(root.path("usage")));
        });
    }

    /**
     * 消费供应商 SSE：过滤空帧、解析增量，并确认真实完成原因，防止把截断当成正常结束。
     *
     * @param target 本次调用所使用的供应商模型与连接快照。
     * @param request 已去除业务路由信息的供应商对话技术请求。
     * @param requestId 贯穿接口、业务调用、供应商请求与日志的关联 ID。
     * @return 按到达顺序发出的供应商增量片段。
     */
    public Flux<ProviderChunkDTO> stream(ProviderModelDTO target, ProviderChatRequestDTO request, String requestId) {
        return Flux.defer(() -> {
            // 第一阶段：每次订阅独立维护完成状态，不能把连接结束或 DONE 当作完成原因。
            // finished：只有明确收到 finish_reason 才置位。
            var finished = new AtomicBoolean();

            // 第二阶段：发送流式请求，先检查 HTTP 状态，再开始消费供应商 SSE。
            // event：各过滤和解析回调中的同一类供应商数据帧，不是对外业务事件。
            return request(target, "/chat/completions", chatBody(target, request, true), requestId)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .bodyValue(chatBody(target, request, true))
                    .exchangeToFlux(response -> {
                        // response：供应商 HTTP 响应；错误响应先释放响应体，避免连接泄漏。
                        observe(response, target, requestId);
                        if (response.statusCode().isError())
                            return response.releaseBody()
                                    .thenMany(Flux.error(status(response.statusCode().value())));
                        return response.bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() { });
                    })
                    // 第三阶段：跳过心跳，消费到 DONE 为止，逐帧按原顺序拆出增量。
                    .filter(event -> event.data() != null && !event.data().isBlank())
                    .takeUntil(event -> "[DONE]".equals(event.data().trim()))
                    .filter(event -> !"[DONE]".equals(event.data().trim()))
                    .concatMapIterable(event -> chunks(parse(event.data()), finished))
                    // 第四阶段：没有真实完成原因的 EOF 是截断错误，交由业务层判断能否降级。
                    .concatWith(Flux.defer(() -> finished.get()
                            ? Flux.empty() : Flux.error(ModelInvocationException.invalid())));
        });
    }

    /**
     * 发送一个文本批次的向量请求，并解析批内索引和向量数值。
     *
     * @param target 本次调用所使用的供应商模型与连接快照。
     * @param texts 本次操作的有序文本输入集合。
     * @param dimensions 本次向量结果使用的维度。
     * @param requestId 贯穿接口、业务调用、供应商请求与日志的关联 ID。
     * @return 未经业务层整组校验的单批向量结果。
     */
    public Mono<ProviderEmbeddingsDTO> embed(ProviderModelDTO target, List<String> texts, int dimensions, String requestId) {
        // 将要发送给供应商的 JSON 请求体。
        ObjectNode body = json.createObjectNode().put("model", target.model());
        body.set("input", json.valueToTree(texts));
        if (target.adjustableDimensions())
            body.put("dimensions", dimensions);
        return post(target, "/embeddings", body, requestId).map(root -> {
            // root：供应商 JSON 响应或当前 SSE 帧的根节点。
            if (!root.path("data").isArray())
                throw ModelInvocationException.invalid();
            // 当前供应商批次中解析出的向量结果。
            List<VectorDTO> vectors = new ArrayList<>();
            // item：供应商当前向量条目的 JSON 节点。
            for (var item : root.path("data")) {
                if (!integer(item.path("index")) || !item.path("embedding").isArray())
                    throw ModelInvocationException.invalid();
                // 当前处理的有序数值或待转换元素集合。
                List<Double> values = new ArrayList<>();
                // value：供应商向量中的单个 JSON 数值，必须可转为有限 double。
                for (var value : item.path("embedding")) {
                    if (!value.isNumber() || !Double.isFinite(value.doubleValue()))
                        throw ModelInvocationException.invalid();
                    values.add(value.doubleValue());
                }
                vectors.add(new VectorDTO(item.path("index").intValue(), values));
            }
            return new ProviderEmbeddingsDTO(vectors, usage(root.path("usage")));
        });
    }

    /**
     * 按供应商原生重排协议组装输入并解析分数，整组只发一次请求。
     *
     * @param target 本次调用所使用的供应商模型与连接快照。
     * @param query 重排所依据的查询文本。
     * @param documents 保持调用方顺序的待重排文档集合。
     * @param topN 最多返回的文档数，业务层将其限制在输入文档数以内。
     * @param requestId 贯穿接口、业务调用、供应商请求与日志的关联 ID。
     * @return 当前供应商返回的原始索引与分数。
     */
    public Mono<ProviderRankingDTO> rerank(ProviderModelDTO target, String query, List<String> documents, int topN, String requestId) {
        // 将要发送给供应商的 JSON 请求体。
        ObjectNode body = json.createObjectNode().put("model", target.model());
        // 当前供应商是否为百炼；其重排协议使用嵌套的 input 和 parameters。
        boolean bailian = target.kind() == ProviderKind.BAILIAN;
        // 重排 query 和 documents 所属对象；百炼嵌套一层，其他协议放在根部。
        ObjectNode input = bailian ? body.putObject("input") : body;
        input.put("query", query);
        input.set("documents", json.valueToTree(documents));
        // 供应商重排选项所在的 JSON 对象。
        ObjectNode params = bailian ? body.putObject("parameters") : body;
        params.put("top_n", topN).put("return_documents", false);
        return post(target, target.rerankUrl(), body, requestId).map(root -> {
            // root：供应商 JSON 响应或当前 SSE 帧的根节点。
            // 供应商原生协议中的重排结果数组。
            JsonNode results = bailian ? root.path("output").path("results") : root.path("results");
            if (!results.isArray())
                throw ModelInvocationException.invalid();
            // 当前处理的文档索引与相关性分数列表。
            List<ScoreDTO> scores = new ArrayList<>();
            // item：供应商当前重排条目的 JSON 节点。
            for (var item : results) {
                if (!integer(item.path("index"))
                        || !item.path("relevance_score").isNumber()
                        || !Double.isFinite(item.path("relevance_score").doubleValue()))
                    throw ModelInvocationException.invalid();
                scores.add(new ScoreDTO(item.path("index").intValue(), item.path("relevance_score").doubleValue()));
            }
            return new ProviderRankingDTO(scores, usage(root.hasNonNull("usage") ? root.path("usage") : root.path("meta").path("tokens")));
        });
    }

    /**
     * 解析完整地址，添加认证与跟踪头，创建尚未发送的 POST 请求。
     *
     * @param target 本次调用所使用的供应商模型与连接快照。
     * @param path 供应商接口的相对路径或完整原生 URL。
     * @param body 将要发送给供应商的 JSON 请求体。
     * @param requestId 贯穿接口、业务调用、供应商请求与日志的关联 ID。
     * @return 可继续设置响应类型和请求体的请求对象。
     */
    private WebClient.RequestBodySpec request(ProviderModelDTO target, String path, ObjectNode body, String requestId) {
        // 本次模型所引用的供应商连接配置。
        var provider = target;
        // 经过选择或拼接后使用的 HTTP(S) 地址。
        String url = path.startsWith("http") ? path : provider.baseUrl().replaceAll("/+$", "") + path;
        return http.post().uri(url).headers(h -> {
            // h：本次供应商请求的 HTTP 头构造器。
            h.setBearerAuth(provider.apiKey());
            h.set("X-Request-Id", requestId);
            if (provider.kind() == ProviderKind.SILICONFLOW)
                h.set("X-Trace-Id", requestId);
        }).contentType(MediaType.APPLICATION_JSON);
    }

    /**
     * 发送 JSON POST 请求，先处理错误状态，再解析非空响应体。
     *
     * @param target 本次调用所使用的供应商模型与连接快照。
     * @param path 供应商接口的相对路径或完整原生 URL。
     * @param body 将要发送给供应商的 JSON 请求体。
     * @param requestId 贯穿接口、业务调用、供应商请求与日志的关联 ID。
     * @return 完成基本协议校验的 JSON 根对象。
     */
    private Mono<JsonNode> post(ProviderModelDTO target, String path, ObjectNode body, String requestId) {
        return request(target, path, body, requestId).bodyValue(body).exchangeToMono(response -> {
            // response：本次供应商 HTTP 响应，用于读取状态、响应头和响应体。
            observe(response, target, requestId);
            if (response.statusCode().isError())
                return response.releaseBody().then(Mono.error(status(response.statusCode().value())));
            return response.bodyToMono(String.class).map(this::parse).switchIfEmpty(Mono.error(ModelInvocationException.invalid()));
        });
    }

    /**
     * 仅记录格式安全的供应商请求标识，不记录凭据、请求正文或模型输出。
     *
     * @param response 本次供应商 HTTP 响应，用于读取状态、响应头和响应体。
     * @param target 本次调用所使用的供应商模型与连接快照。
     * @param requestId 贯穿接口、业务调用、供应商请求与日志的关联 ID。
     */
    private void observe(ClientResponse response, ProviderModelDTO target, String requestId) {
        // 供应商返回的关联 ID，只有通过字符集和长度检查后才记录。
        String upstreamId = response.headers().asHttpHeaders().getFirst("X-Request-Id");
        if (upstreamId == null)
            upstreamId = response.headers().asHttpHeaders().getFirst("X-DashScope-Request-Id");
        if (upstreamId != null && upstreamId.matches("[A-Za-z0-9._:-]{1,128}"))
            log.debug("ai upstream requestId={} upstreamRequestId={} model={} provider={}", requestId, upstreamId, target.alias(), target.provider());
    }

    /**
     * 把归一化消息、图片和工具转换成供应商兼容协议，并显式设置思考开关。
     *
     * @param target 本次调用所使用的供应商模型与连接快照。
     * @param request 已去除业务路由信息的供应商对话技术请求。
     * @param stream 是否使用供应商流式响应模式。
     * @return 可直接发送的对话 JSON 请求体。
     */
    private ObjectNode chatBody(ProviderModelDTO target, ProviderChatRequestDTO request, boolean stream) {
        // 本次操作选中的模型配置，用于能力、维度、批次和输出限制。
        var model = target;

        // 第一阶段：模型、输出上限与消息结构使用统一兼容协议。
        // 将要发送给供应商的 JSON 请求体。
        ObjectNode body = json.createObjectNode()
                .put("model", model.model())
                .put("stream", stream)
                .put("max_tokens", request.maxOutputTokens() == null
                        ? model.maxOutputTokens() : Math.min(request.maxOutputTokens(), model.maxOutputTokens()));
        // 按原有对话顺序组织的消息集合。
        ArrayNode messages = body.putArray("messages");
        // 将快捷 text 输入补成 user 消息后得到的有序消息列表。
        List<MessageDTO> source = request.messages() == null
                ? List.of(new MessageDTO("user", request.text(), null, null, null, null))
                : request.messages();
        // message：当前有序对话消息，包含角色、文本及可选图片或工具续接。
        for (var message : source) {
            // 正在组装的一条供应商消息 JSON 对象。
            ObjectNode item = messages.addObject().put("role", message.role());
            if (message.images() != null && !message.images().isEmpty()) {
                // 多模态消息的内容片段，依次保存文本与图片引用。
                ArrayNode parts = item.putArray("content");
                if (message.content() != null)
                    parts.addObject().put("type", "text").put("text", message.content());
                // image：当前消息中的图片引用或 Base64 内容。
                for (var image : message.images()) {
                    // 经过选择或拼接后使用的 HTTP(S) 地址。
                    String url = image.url() != null ? image.url() : "data:" + image.mediaType() + ";base64," + image.base64();
                    parts.addObject().put("type", "image_url").putObject("image_url").put("url", url);
                }
            } else if (message.content() != null)
                item.put("content", message.content());
            else
                item.putNull("content");
            if (message.reasoning() != null)
                item.put("reasoning_content", message.reasoning());
            if (message.toolCallId() != null)
                item.put("tool_call_id", message.toolCallId());
            if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
                // 当前消息中按顺序解析或组装的工具调用列表。
                var calls = item.putArray("tool_calls");
                // call：当前工具调用或工具增量，携带函数名、参数与关联 ID。
                for (var call : message.toolCalls())
                    calls.addObject().put("id", call.id()).put("type", "function")
                            .putObject("function").put("name", call.name()).put("arguments", call.arguments());
            }
        }

        // 第二阶段：按供应商设置原生思考开关，关闭时也显式发送，避免默认行为漂移。
        if (model.thinking()) {
            if (target.kind() == ProviderKind.DEEPSEEK)
                body.putObject("thinking").put("type", Boolean.TRUE.equals(request.thinking()) ? "enabled" : "disabled");
            else
                body.put("enable_thinking", Boolean.TRUE.equals(request.thinking()));
        }

        // 第三阶段：追加 JSON、工具和流式用量选项，工具定义只转发，不执行。
        if (Boolean.TRUE.equals(request.json()))
            body.putObject("response_format").put("type", "json_object");
        if (request.tools() != null && !request.tools().isEmpty()) {
            // 当前操作声明的工具定义集合。
            var tools = body.putArray("tools");
            // tool：当前工具定义或工具调用业务数据。
            for (var tool : request.tools()) {
                // 供应商协议中的 function 对象，保存工具名称与参数。
                var fn = tools.addObject().put("type", "function").putObject("function").put("name", tool.name());
                if (tool.description() != null)
                    fn.put("description", tool.description());
                fn.set("parameters", tool.parameters());
            }
        }
        if (request.toolChoice() != null)
            body.set("tool_choice", request.toolChoice());
        if (stream)
            body.putObject("stream_options").put("include_usage", true);
        return body;
    }

    /**
     * 将一个 SSE 数据对象拆成推理、正文、工具、完成和用量片段，保留尾部用量。
     *
     * @param root 供应商 JSON 响应或当前 SSE 帧的根节点。
     * @param finished 是否已经收到真实 finish_reason，不能用 DONE 标记替代。
     * @return 当前帧解析出的有序片段列表。
     */
    private List<ProviderChunkDTO> chunks(JsonNode root, AtomicBoolean finished) {
        // 当前业务处理或对象转换得到的结果。
        List<ProviderChunkDTO> result = new ArrayList<>();
        // choice：供应商返回的模型候选，当前仅处理第一个对话候选。
        for (var choice : root.path("choices")) {
            if (choice.path("index").asInt(0) != 0)
                continue;
            // 供应商当前流帧中的增量消息节点。
            var delta = choice.path("delta");
            // 模型独立返回的推理内容，也可用于工具回合续接。
            String reasoning = optionalText(delta, "reasoning_content");
            // 模型返回的正文内容，与推理文本分开保存。
            String content = optionalText(delta, "content");
            if (reasoning != null && !reasoning.isEmpty())
                result.add(new ProviderChunkDTO("reasoning", reasoning, null, null, null));
            if (content != null && !content.isEmpty())
                result.add(new ProviderChunkDTO("content", content, null, null, null));
            // call：当前工具调用或工具增量，携带函数名、参数与关联 ID。
            for (var call : delta.path("tool_calls")) {
                if (!integer(call.path("index")))
                    throw ModelInvocationException.invalid();
                result.add(new ProviderChunkDTO("tool", null, tool(call, call.path("index").intValue()), null, null));
            }
            if (choice.path("finish_reason").isTextual()) {
                finished.set(true);
                result.add(new ProviderChunkDTO("finish", null, null, null, choice.path("finish_reason").asText()));
            }
        }
        // 供应商报告的真实用量；缺失时保持 null。
        // 用量可能在完成原因之后单独到达，不能因为已有 finish_reason 就丢弃尾帧。
        ProviderUsageDTO usage = usage(root.path("usage"));
        if (usage != null)
            result.add(new ProviderChunkDTO("usage", null, null, usage, null));
        return result;
    }

    /**
     * 从供应商函数调用结构读取增量字段，允许后续帧补齐字段。
     *
     * @param call 当前工具调用或工具增量，携带函数名、参数与关联 ID。
     * @param index 同一轮工具调用的零起始位置，增量根据此值累加。
     * @return 带工具位置的归一化工具调用。
     */
    private ProviderToolCallDTO tool(JsonNode call, int index) {
        // 供应商协议中的 function 对象，保存工具名称与参数。
        var fn = call.path("function");
        return new ProviderToolCallDTO(optionalText(call, "id"), optionalText(fn, "name"), optionalText(fn, "arguments"), index);
    }

    /**
     * 解析非空 JSON 对象，并将供应商 error 对象统一视为无效响应。
     *
     * @param value 当前待检查或转换的单个值。
     * @return 通过结构校验的 JSON 根节点。
     */
    private JsonNode parse(String value) {
        try {
            // 供应商 JSON 响应或当前 SSE 帧的根节点。
            JsonNode root = json.readTree(value);
            if (root == null || !root.isObject() || root.hasNonNull("error"))
                throw ModelInvocationException.invalid();
            return root;
        } catch (JsonProcessingException e) {
            // e：供应商 JSON 解码失败的原因，统一映射为无效上游响应。
            throw ModelInvocationException.invalid();
        }
    }

    /**
     * 读取真实用量；供应商未返回对象时保留未知状态。
     *
     * @param node 当前解析或检查的 JSON 节点。
     * @return 真实用量对象，或表示未知的 null。
     */
    private ProviderUsageDTO usage(JsonNode node) {
        if (!node.isObject())
            return null;
        return new ProviderUsageDTO(
                number(node, "prompt_tokens", "input_tokens"),
                number(node, "completion_tokens", "output_tokens"),
                number(node, "total_tokens", "tokens"));
    }

    /**
     * 兼容两种供应商字段名，读取非负整数计数，不把缺失字段补成零。
     *
     * @param node 当前解析或检查的 JSON 节点。
     * @param first 优先读取的供应商计数字段名。
     * @param second 兼容另一种协议的备用计数字段名。
     * @return 真实计数，或缺失时的 null。
     */
    private Long number(JsonNode node, String first, String second) {
        // 当前供应商字段对应的 JSON 值，需校验类型后再读取。
        JsonNode value = node.hasNonNull(first) ? node.path(first) : node.path(second);
        if (value.isMissingNode() || value.isNull())
            return null;
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0)
            throw ModelInvocationException.invalid();
        return value.longValue();
    }

    /**
     * 读取可缺失的文本字段，拒绝用其他 JSON 类型伪装文本。
     *
     * @param node 当前解析或检查的 JSON 节点。
     * @param key 当前需要读取或写入的字段名。
     * @return 文本内容，或字段缺失时的 null。
     */
    private String optionalText(JsonNode node, String key) {
        // 当前供应商字段对应的 JSON 值，需校验类型后再读取。
        JsonNode value = node.path(key);
        if (value.isNull() || value.isMissingNode())
            return null;
        if (!value.isTextual())
            throw ModelInvocationException.invalid();
        return value.textValue();
    }

    /**
     * 判断 JSON 数值能否无损转换为 Java int。
     *
     * @param value 当前供应商字段对应的 JSON 值，需校验类型后再读取。
     * @return 是可表示的整数时为 true。
     */
    private boolean integer(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToInt();
    }

    /**
     * 将供应商 HTTP 状态映射为对外错误、降级策略和熔断计数策略。
     *
     * @param status 对外使用的 HTTP 状态码。
     * @return 按输入、认证、限流或其他上游错误分类的异常。
     */
    private ModelInvocationException status(int status) {
        if (status == 400 || status == 413 || status == 422)
            return new ModelInvocationException(400, "UPSTREAM_INPUT", "Upstream rejected input or context size", false, false);
        if (status == 401 || status == 403)
            return new ModelInvocationException(502, "AUTH", "Upstream authentication failed", true, false);
        if (status == 429)
            return new ModelInvocationException(503, "RATE_LIMIT", "Upstream rate limited", true, true);
        return new ModelInvocationException(502, "UPSTREAM", "Upstream request failed", status >= 500, status >= 500);
    }
}
