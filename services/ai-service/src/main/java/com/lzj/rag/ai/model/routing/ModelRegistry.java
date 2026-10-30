package com.lzj.rag.ai.model.routing;

import com.lzj.rag.ai.infra.provider.enums.ProviderKind;
import com.lzj.rag.ai.infra.provider.exception.ModelInvocationException;
import com.lzj.rag.ai.model.config.AiProperties;
import com.lzj.rag.ai.model.enums.ModelCapability;
import com.lzj.rag.ai.model.enums.ModelTier;
import com.lzj.rag.ai.model.service.bo.ChatBO;
import com.lzj.rag.ai.model.service.bo.EmbeddingModelBO;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import org.springframework.stereotype.Component;
import static com.lzj.rag.ai.model.config.AiProperties.*;

/**
 * 模型配置注册器，负责启动校验、可用性过滤和嵌入配置身份计算。
 */
@Component
public class ModelRegistry {

    /**
     * 模型与供应商的完整配置快照。
     */
    private final AiProperties config;

    /**
     * 启动时检查限制、连接配置、模型引用、能力与默认路由，尽早拒绝无效部署配置。
     *
     * @param config 模型与供应商的完整配置快照。
     */
    public ModelRegistry(AiProperties config) {
        this.config = config;
        // 应用配置的输入、并发、熔断和时间限制。
        var l = config.limits();
        require(l.maxAttempts() > 0
                && l.concurrency() > 0
                && l.circuitFailures() > 0
                && l.maxResponseBytes() > 0
                && l.maxInputChars() > 0
                && l.maxInputs() > 0
                && l.maxImages() > 0
                && l.maxImageBytes() > 0, "AI limits must be positive");
        // d：待校验的超时或冷却持续时间，必须严格为正数。
        for (var d : List.of(l.totalTimeout(), l.connectTimeout(), l.firstTokenTimeout(), l.streamIdleTimeout(), l.circuitCooldown())) positive(d);

        // 第二阶段：检查连接地址，再检查每个模型的供应商、能力和向量身份。
        config.providers().forEach((id, p) -> {
            // id：当前具名配置项的逻辑标识。
            // p：当前模型所引用或正在校验的供应商配置。
            require(p.kind() != null, "Provider kind required: " + id);
            url(p.baseUrl());
            if (p.rerankUrl() != null)
                url(p.rerankUrl());
        });
        config.models().forEach((id, m) -> {
            // id：当前具名配置项的逻辑标识。
            // m：当前逻辑模型配置，用于检查供应商引用、能力和向量身份。
            require(config.providers().containsKey(m.provider()), "Unknown model provider: " + id);
            require(m.model() != null && !m.model().isBlank() && !m.capabilities().isEmpty(), "Invalid model: " + id);
            require(m.maxOutputTokens() > 0 && m.batchSize() > 0, "Invalid model limits: " + id);
            if (m.timeout() != null)
                positive(m.timeout());
            if (m.capabilities().contains(ModelCapability.EMBEDDING)) {
                require(m.configVersion() != null
                        && !m.configVersion().isBlank()
                        && m.configVersion().length() <= 256, "Embedding configVersion required: " + id);
                require(m.dimensions() != null
                        && m.dimensions() > 0
                        && m.embeddingSpace() != null
                        && !m.embeddingSpace().isBlank(), "Embedding identity required: " + id);
                // d：模型允许的一个可选向量维度，必须非空且为正数。
                require(m.allowedDimensions().stream().allMatch(d -> d != null && d > 0), "Invalid dimensions: " + id);
                require(m.allowedDimensions().isEmpty() || m.allowedDimensions().contains(m.dimensions()), "Default dimensions not allowed: " + id);
            }
            if (m.capabilities().contains(ModelCapability.RERANK)) {
                // 当前模型所引用或正在校验的供应商配置。
                var p = config.providers().get(m.provider());
                require(p.kind() != ProviderKind.DEEPSEEK && p.rerankUrl() != null, "Unsupported rerank mapping: " + id);
            }
        });

        // 第三阶段：检查路由引用和默认操作模型，拒绝悬空别名。
        config.routes().forEach((task, r) -> {
            // task：本次调用使用的业务路由名称，省略时按普通对话或视觉模式选择默认任务。
            // r：当前任务路由的默认档位与候选配置。
            require(!task.isBlank() && r.defaultTier() != null, "Invalid task route");
            r.candidates().values().forEach(this::references);
        });
        reference(config.embedding().defaultModel());
        references(config.embedding().fallbacks());
        reference(config.rerank().defaultModel());
        references(config.rerank().fallbacks());
        require(model(config.embedding().defaultModel()).capabilities().contains(ModelCapability.EMBEDDING), "Default embedding model lacks capability");
        require(model(config.rerank().defaultModel()).capabilities().contains(ModelCapability.RERANK), "Default rerank model lacks capability");

        // 第四阶段：启用 AI 时，每个对话档位至少需要一个配置可用的聊天候选。
        if (config.enabled()) {
            require(config.routes().containsKey("chat"), "Chat route required");
            // tier：本次调用的候选优先级档位，不等同于思考模式。
            for (ModelTier tier : ModelTier.values())
                require(!route("chat", tier, null, Set.of(ModelCapability.CHAT)).isEmpty(),
                        "No active CHAT candidate for " + tier + "; configure a key or AI_ENABLED=false");
        }
    }

    /**
     * 读取已绑定的模型业务配置。
     * @return 当前应用的 AI 配置。
     */
    public AiProperties config() {
        return config;
    }

    /**
     * 按逻辑别名查询模型，不允许未知别名静默退回默认值。
     *
     * @param alias 应用内部的逻辑模型别名，不是供应商原生模型名。
     * @return 对应的模型配置。
     */
    public Model model(String alias) {
        // 当前逻辑模型配置，用于检查供应商引用、能力和向量身份。
        var m = config.models().get(alias);
        if (m == null)
            throw ModelInvocationException.input("Unknown logical model");
        return m;
    }

    /**
     * 从逻辑模型所引用的供应商标识查找连接配置。
     *
     * @param alias 应用内部的逻辑模型别名，不是供应商原生模型名。
     * @return 该模型所属供应商的配置。
     */
    public Provider provider(String alias) {
        return config.providers().get(model(alias).provider());
    }

    /**
     * 校验嵌入能力和目标维度，计算排除凭据及 URL 的配置身份摘要。
     *
     * @param alias 应用内部的逻辑模型别名，不是供应商原生模型名。
     * @param requestedDimensions 调用方期望的嵌入维度，未指定时使用模型默认值。
     * @return 可公开且可用于请求修订校验的嵌入模型描述。
     */
    public EmbeddingModelBO embeddingDescriptor(String alias, Integer requestedDimensions) {
        // 当前逻辑模型配置，用于检查供应商引用、能力和向量身份。
        var m = model(alias);
        if (!m.capabilities().contains(ModelCapability.EMBEDDING))
            throw ModelInvocationException.input("Model lacks embedding capability");
        // 通过允许范围校验后的实际向量维度。
        int dimension = requestedDimensions == null ? m.dimensions() : requestedDimensions;
        if (dimension <= 0
                || (m.allowedDimensions().isEmpty() ? !Objects.equals(m.dimensions(), dimension) : !m.allowedDimensions().contains(dimension)))
            throw ModelInvocationException.input("Unsupported embedding dimensions");

        // 配置修订只包含嵌入语义身份，不包含密钥或连接地址；语义变化需更新 configVersion。
        try {
            // 用于长度前缀编码嵌入身份字段的内存字节流。
            var bytes = new java.io.ByteArrayOutputStream();
            // out：写入嵌入身份字段的长度前缀二进制输出流。
            try (var out = new java.io.DataOutputStream(bytes)) {
                // value：参与身份摘要的一个语义字段，按长度前缀顺序写入。
                for (String value : List.of(
                        alias, m.provider(), provider(alias).kind().name(), m.model(),
                        Integer.toString(dimension), m.embeddingSpace(), m.configVersion()))
                    out.writeUTF(value);
            }
            // 依据嵌入语义身份计算的 SHA-256 修订摘要。
            String revision = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
            return new EmbeddingModelBO(
                    alias, m.provider(), provider(alias).kind(), m.model(), dimension,
                    m.embeddingSpace(), m.configVersion(), revision, m.batchSize(),
                    config.enabled() && active(alias), "embed-v1");
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException error) {
            // error：身份编码或摘要算法出现的内部异常。
            throw new IllegalStateException(error);
        }
    }

    /**
     * 判断模型和供应商均启用且配置了非空密钥，不执行真实账户探测。
     *
     * @param alias 应用内部的逻辑模型别名，不是供应商原生模型名。
     * @return 本地配置允许参与路由时为 true。
     */
    public boolean active(String alias) {
        // 当前逻辑模型配置，用于检查供应商引用、能力和向量身份。
        var m = model(alias);
        // 当前模型所引用或正在校验的供应商配置。
        var p = provider(alias);
        return m.enabled() && p.enabled() && p.apiKey() != null && !p.apiKey().isBlank();
    }

    /**
     * 从视觉、思考、JSON、工具与续接消息推导所需能力，再生成有序候选。
     *
     * @param request 当前操作的输入请求数据。
     * @param vision 是否按视觉请求校验图片并要求候选具备视觉能力。
     * @return 启用且满足全部能力的对话候选。
     */
    public List<String> chat(ChatBO request, boolean vision) {
        enabled();
        // 本次请求必须同时满足的模型能力集合。
        Set<ModelCapability> needed = EnumSet.of(ModelCapability.CHAT);
        if (vision)
            needed.add(ModelCapability.VISION);
        if (Boolean.TRUE.equals(request.thinking()))
            needed.add(ModelCapability.THINKING);
        if (Boolean.TRUE.equals(request.json()))
            needed.add(ModelCapability.JSON);
        if (request.tools() != null && !request.tools().isEmpty())
            needed.add(ModelCapability.TOOLS);
        // m：当前续接消息，用于判断是否需要工具调用能力。
        if (request.messages() != null
                && request.messages().stream().anyMatch(m -> m.toolCallId() != null
                || (m.toolCalls() != null
                && !m.toolCalls().isEmpty())))
            needed.add(ModelCapability.TOOLS);
        return available(route(
                request.task() == null ? (vision ? "vision" : "chat") : request.task(),
                request.tier(), request.preferredModel(), needed));
    }

    /**
     * 将兼容的首选模型放到档位候选前，去重后过滤不可用或能力不足的模型。
     *
     * @param task 本次调用使用的业务路由名称，省略时按普通对话或视觉模式选择默认任务。
     * @param tier 本次调用的候选优先级档位，不等同于思考模式。
     * @param preferred 调用方指定的首选逻辑模型别名。
     * @param needed 本次请求必须同时满足的模型能力集合。
     * @return 保持优先级的逻辑模型别名列表。
     */
    public List<String> route(String task, ModelTier tier, String preferred, Set<ModelCapability> needed) {
        // 当前任务对应的档位与有序候选配置。
        var route = config.routes().get(task);
        if (route == null)
            throw ModelInvocationException.input("Unknown task");
        // 保留插入顺序并自动去重的模型候选集合。
        LinkedHashSet<String> aliases = new LinkedHashSet<>();
        if (preferred != null) {
            if (!model(preferred).capabilities().containsAll(needed))
                throw ModelInvocationException.input("Preferred model lacks required capability");
            aliases.add(preferred);
        }
        aliases.addAll(route.candidates().getOrDefault(tier == null ? route.defaultTier() : tier, List.of()));
        // a：当前待检查或创建熔断状态的逻辑模型别名。
        return aliases.stream().filter(a -> active(a) && model(a).capabilities().containsAll(needed)).toList();
    }

    /**
     * 为向量或重排操作拼接锚点与降级候选，并按能力和启用状态过滤。
     *
     * @param anchor 本次操作固定的首个逻辑模型，用于约束向量身份或优先顺序。
     * @param fallback 锚点之后允许依次尝试的逻辑模型别名列表。
     * @param capability 当前操作要求的模型能力。
     * @return 与本次操作能力相符的有序候选。
     */
    public List<String> operation(String anchor, List<String> fallback, ModelCapability capability) {
        enabled();
        model(anchor);
        if (!model(anchor).capabilities().contains(capability))
            throw ModelInvocationException.input("Model lacks required capability");
        // 当前业务处理或对象转换得到的结果。
        LinkedHashSet<String> result = new LinkedHashSet<>();
        result.add(anchor);
        result.addAll(fallback);
        // a：当前待检查或创建熔断状态的逻辑模型别名。
        return result.stream().filter(a -> active(a) && model(a).capabilities().contains(capability)).toList();
    }

    /**
     * 保证候选列表非空，使无路由情况在调用供应商前明确失败。
     *
     * @param candidates 已按优先级排序并过滤可用性的逻辑模型别名列表。
     * @return 已确认至少包含一个候选的原列表。
     */
    public List<String> available(List<String> candidates) {
        if (candidates.isEmpty())
            throw ModelInvocationException.unavailable("No active compatible model configured");
        return candidates;
    }

    /**
     * 检查全局 AI 功能开关，禁用时立即返回不可用错误。
     */
    public void enabled() {
        if (!config.enabled())
            throw ModelInvocationException.unavailable("AI service is disabled");
    }

    /**
     * 逐个检查配置中的逻辑模型引用是否存在。
     *
     * @param ids 需要逐项验证的逻辑模型引用列表。
     */
    private void references(List<String> ids) {
        ids.forEach(this::reference);
    }

    /**
     * 检查单个模型引用非空且已经定义。
     *
     * @param id 当前具名配置项的逻辑标识。
     */
    private void reference(String id) {
        require(id != null && config.models().containsKey(id), "Unknown model reference: " + id);
    }

    /**
     * 校验持续时间存在且严格为正数。
     *
     * @param d 待校验的超时或冷却持续时间，必须严格为正数。
     */
    private static void positive(Duration d) {
        require(d != null && !d.isNegative() && !d.isZero(), "Duration must be positive");
    }

    /**
     * 校验 HTTP(S) 地址，并禁止凭据、片段和查询参数混入配置根地址。
     *
     * @param value 待验证的供应商 API 根地址或原生重排完整地址。
     */
    private static void url(String value) {
        try {
            // 解析后的图片或供应商地址，用于校验协议、主机和凭据信息。
            var uri = URI.create(value);
            require(Set.of("https", "http").contains(uri.getScheme())
                    && uri.getHost() != null
                    && uri.getUserInfo() == null
                    && uri.getFragment() == null
                    && uri.getQuery() == null, "Invalid provider URL");
        } catch (RuntimeException e) {
            // e：地址解析或地址约束检查失败的原因。
            throw new IllegalArgumentException("Invalid provider URL");
        }
    }

    /**
     * 在配置约束不成立时抛出启动校验异常。
     *
     * @param test 当前配置约束的判断结果。
     * @param message 需要保留给调用方或启动诊断的错误说明。
     */
    private static void require(boolean test, String message) {
        if (!test)
            throw new IllegalArgumentException(message);
    }
}
