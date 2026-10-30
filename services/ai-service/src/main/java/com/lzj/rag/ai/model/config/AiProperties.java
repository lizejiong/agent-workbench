package com.lzj.rag.ai.model.config;

import com.lzj.rag.ai.infra.provider.enums.ProviderKind;
import com.lzj.rag.ai.model.enums.ModelCapability;
import com.lzj.rag.ai.model.enums.ModelTier;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.*;

/**
 * 绑定 ai.* 配置；启动时由模型注册器检查引用、能力和参数范围。
 *
 * @param enabled 是否允许调用模型；关闭后仍可使用健康检查与文本工具。
 * @param providers 供应商标识到连接配置的映射。
 * @param models 逻辑模型别名到模型配置的映射。
 * @param routes 业务任务名称到路由规则的映射。
 * @param embedding 向量调用的默认模型与兼容降级配置。
 * @param rerank 重排调用的默认模型与降级配置。
 * @param limits 本次操作应遵守的资源与时间限制。
 */
@ConfigurationProperties("ai")
public record AiProperties(
        @DefaultValue("true") boolean enabled,
        Map<String, Provider> providers,
        Map<String, Model> models,
        Map<String, Route> routes,
        @DefaultValue Embedding embedding,
        @DefaultValue Rerank rerank,
        @DefaultValue Limits limits) {

    /** 将缺失的供应商、模型和路由映射归一化为空映射，便于统一校验。 */
    public AiProperties {
        providers = providers == null ? Map.of() : providers;
        models = models == null ? Map.of() : models;
        routes = routes == null ? Map.of() : routes;
    }

    /**
     * 供应商连接配置；空密钥表示不参与实际调用。
     *
     * @param kind 供应商原生协议类型。
     * @param baseUrl 供应商兼容模式 API 根地址，由客户端追加具体接口路径。
     * @param apiKey 供应商访问密钥，禁止出现在日志和对外响应中。
     * @param rerankUrl 供应商原生重排接口的完整 URL，不再追加路径。
     * @param enabled 当前配置项的启用标记。
     */
    public record Provider(
            ProviderKind kind,
            String baseUrl,
            String apiKey,
            String rerankUrl,
            @DefaultValue("true") boolean enabled) {}

    /**
     * 逻辑模型配置，统一描述原生模型、能力、资源限制和向量空间身份。
     *
     * @param provider 供应商配置标识，用于关联 ai.providers 中的连接配置。
     * @param model 供应商原生模型名称，实际发送到供应商接口的 model 字段。
     * @param enabled 当前配置项的启用标记。
     * @param capabilities 模型明确声明支持的能力集合，用于路由前置校验。
     * @param maxOutputTokens 期望或配置允许的输出 Token 上限，实际调用取请求与模型上限的较小值。
     * @param dimensions 本次向量结果使用的维度。
     * @param allowedDimensions 供应商明确支持的可选向量维度；为空表示只接受固定维度。
     * @param embeddingSpace 向量语义空间标识，仅相同空间和相同实际维度才允许兼容降级。
     * @param batchSize 该向量模型每次请求最多处理的文本条数。
     * @param timeout 模型配置的单次候选超时，可为空。
     * @param configVersion 部署方维护的嵌入语义版本，模型语义变化时必须更新。
     */
    public record Model(
            String provider,
            String model,
            @DefaultValue("true") boolean enabled,
            Set<ModelCapability> capabilities,
            @DefaultValue("8192") int maxOutputTokens,
            Integer dimensions,
            List<Integer> allowedDimensions,
            String embeddingSpace,
            @DefaultValue("32") int batchSize,
            Duration timeout,
            @DefaultValue("v1") String configVersion) {

        /** 将缺失的能力和可选维度列表归一化为空集合。 */
        public Model {
            capabilities = capabilities == null ? Set.of() : capabilities;
            allowedDimensions = allowedDimensions == null ? List.of() : allowedDimensions;
        }
    }

    /**
     * 一个业务任务的路由规则，按调用档位保存有序候选。
     *
     * @param defaultTier 请求未指定调用档位时使用的默认档位。
     * @param candidates 已按优先级排序并过滤可用性的逻辑模型别名列表。
     */
    public record Route(
            @DefaultValue("STANDARD") ModelTier defaultTier,
            Map<ModelTier, List<String>> candidates) {

        /** 将缺失的候选表归一化为空映射。 */
        public Route {
            candidates = candidates == null ? Map.of() : candidates;
        }
    }

    /**
     * 向量调用的默认锚点与兼容降级候选。
     *
     * @param defaultModel 请求未指定模型时使用的默认逻辑别名。
     * @param fallbacks 默认锚点失败后允许按顺序尝试的逻辑模型别名。
     */
    public record Embedding(String defaultModel, List<String> fallbacks) {

        /** 将缺失的向量降级列表归一化为空列表。 */
        public Embedding {
            fallbacks = fallbacks == null ? List.of() : fallbacks;
        }
    }

    /**
     * 重排调用的默认模型与有序降级候选。
     *
     * @param defaultModel 请求未指定模型时使用的默认逻辑别名。
     * @param fallbacks 默认锚点失败后允许按顺序尝试的逻辑模型别名。
     */
    public record Rerank(String defaultModel, List<String> fallbacks) {

        /** 将缺失的重排降级列表归一化为空列表。 */
        public Rerank {
            fallbacks = fallbacks == null ? List.of() : fallbacks;
        }
    }

    /**
     * 请求预算、并发、熔断和输入输出大小限制。
     *
     * @param maxAttempts 一个完整逻辑请求允许实际发起的最大候选尝试次数。
     * @param concurrency 进程内允许同时执行的最大逻辑请求数量。
     * @param totalTimeout 覆盖候选降级、向量所有批次和持续输出的总时间上限。
     * @param connectTimeout 建立供应商网络连接允许的最长时间。
     * @param firstTokenTimeout 等到首个有效输出片段的最长时间，空帧与心跳不会重置它。
     * @param streamIdleTimeout 流已有可见输出后，相邻上游片段之间允许的最大空闲时间。
     * @param circuitFailures 连续计数失败多少次后打开逻辑模型熔断器。
     * @param circuitCooldown 熔断打开后等待半开探测的冷却时长。
     * @param maxResponseBytes 上游单个响应或 SSE 数据帧的最大缓冲字节数。
     * @param maxInputChars 一组逻辑输入允许的累计字符数上限。
     * @param maxInputs 一组输入允许的最大消息数或文本条数。
     * @param maxImages 单次视觉请求允许的最大图片数量。
     * @param maxImageBytes 单张 Base64 图片解码后的最大字节数。
     */
    public record Limits(
            @DefaultValue("3") int maxAttempts,
            @DefaultValue("32") int concurrency,
            @DefaultValue("90s") Duration totalTimeout,
            @DefaultValue("5s") Duration connectTimeout,
            @DefaultValue("20s") Duration firstTokenTimeout,
            @DefaultValue("20s") Duration streamIdleTimeout,
            @DefaultValue("3") int circuitFailures,
            @DefaultValue("30s") Duration circuitCooldown,
            @DefaultValue("4194304") int maxResponseBytes,
            @DefaultValue("1000000") int maxInputChars,
            @DefaultValue("128") int maxInputs,
            @DefaultValue("4") int maxImages,
            @DefaultValue("2097152") int maxImageBytes) {}
}
