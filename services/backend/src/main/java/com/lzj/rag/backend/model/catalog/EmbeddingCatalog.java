package com.lzj.rag.backend.model.catalog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lzj.rag.backend.knowledgebase.exception.KnowledgeBaseException;
import com.lzj.rag.backend.model.service.bo.EmbeddingProfileBO;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** 校验并公开可用嵌入配置，为本地配置计算稳定修订标识。 */
@Component
@EnableConfigurationProperties(EmbeddingCatalog.Properties.class)
public class EmbeddingCatalog {

    /**
     * 可绑定嵌入配置的集合。
     *
     * @param defaultProfile 未显式指定时使用的解析档位。
     * @param profiles 按公开配置标识索引的嵌入模型集合。
     */
    @Validated
    @ConfigurationProperties("kb.models")
    public record Properties(
            @NotBlank String defaultProfile,
            @NotEmpty Map<@NotBlank String, @Valid Model> profiles) {}

    /**
     * 单个嵌入配置的模型身份、维度和向量空间描述。
     *
     * @param enabled 是否启用该资源或能力。
     * @param modelId 模型逻辑标识。
     * @param provider 模型供应商标识。
     * @param modelName 供应商原生模型名称。
     * @param dimension 向量维度。
     * @param embeddingSpace 嵌入向量空间身份，模型名称相同也不能跨空间混用。
     * @param configVersion 嵌入模型声明的配置版本。
     */
    public record Model(
            boolean enabled,
            @NotBlank String modelId,
            @NotBlank String provider,
            @NotBlank String modelName,
            @Min(1024) @Max(1024) int dimension,
            @NotBlank String embeddingSpace,
            @NotBlank String configVersion) {}

    /**
     * 校验完成的模型绑定及其稳定本地修订标识。
     *
     * @param profile 公开配置或解析策略标识。
     * @param snapshot 本次模型调用或状态变更所依据的不可变业务快照。
     */
    public record Binding(EmbeddingProfileBO profile, String snapshot) {}

    /** 当前组件使用的业务配置。 */
    private final Properties properties;

    /** 用于业务请求、持久化快照与元数据转换的 JSON 映射器。 */
    private final ObjectMapper json;

    /**
     * 初始化当前组件，保存业务依赖及本次使用的配置。
     *
     * @param properties 当前组件使用的业务配置。
     * @param json 用于业务请求、持久化快照与元数据转换的 JSON 映射器。
     */
    public EmbeddingCatalog(Properties properties, ObjectMapper json) {
        this.properties = properties;
        this.json = json;
        if (!properties.profiles().containsKey(properties.defaultProfile())) {
            throw new IllegalStateException("Default embedding profile is not configured");
        }
    }

    /**
     * 根据公开配置标识查找并返回已校验的嵌入绑定。
     *
     * @param requested 当前接口或请求声明的策略选择。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public Binding resolve(String requested) {
        // id：当前业务对象的唯一标识。
        String id = requested == null ? properties.defaultProfile() : requested;
        // model：当前固定的模型配置或 AI 返回的模型身份节点。
        Model model = properties.profiles().get(id);
        if (model == null || !model.enabled()) {
            throw new KnowledgeBaseException(
                    400, "KB_PROFILE_NOT_ALLOWED", "Select an enabled server embedding profile");
        }
        try {
            // snapshot：本次模型调用或状态变更所依据的不可变业务快照。
            String snapshot = json.writeValueAsString(model);
            // revision：当前同步数据或配置使用的修订标识。
            String revision = sha256(snapshot);
            return new Binding(
                    new EmbeddingProfileBO(
                            id,
                            model.modelId(),
                            model.provider(),
                            model.modelName(),
                            model.dimension(),
                            model.embeddingSpace(),
                            model.configVersion(),
                            revision),
                    snapshot);
        } catch (JsonProcessingException error) {
            // error：本次处理捕获的异常，用于归类失败原因。
            throw new IllegalStateException("Invalid embedding configuration", error);
        }
    }

    /**
     * 列出允许被知识库绑定的公开嵌入配置。
     *
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public List<EmbeddingProfileBO> available() {
        // e：当前公开配置标识及其模型定义的键值项。
        // e：当前公开配置标识及其模型定义的键值项。
        return properties.profiles().entrySet().stream()
                .filter(e -> e.getValue().enabled())
                .map(e -> resolve(e.getKey()).profile())
                .sorted(Comparator.comparing(EmbeddingProfileBO::id))
                .toList();
    }

    /**
     * 对 UTF-8 文本计算 SHA-256 十六进制摘要，用作稳定内容或配置标识。
     *
     * @param value 当前待校验、转换或写入的值。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            // error：本次处理捕获的异常，用于归类失败原因。
            throw new IllegalStateException(error);
        }
    }
}
