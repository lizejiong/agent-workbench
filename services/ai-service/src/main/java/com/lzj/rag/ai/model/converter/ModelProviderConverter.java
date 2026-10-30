package com.lzj.rag.ai.model.converter;

import com.lzj.rag.ai.infra.provider.dto.ProviderChatRequestDTO;
import com.lzj.rag.ai.infra.provider.dto.ProviderChunkDTO;
import com.lzj.rag.ai.infra.provider.dto.ProviderModelDTO;
import com.lzj.rag.ai.infra.provider.dto.ProviderToolCallDTO;
import com.lzj.rag.ai.infra.provider.dto.ProviderUsageDTO;
import com.lzj.rag.ai.model.config.AiProperties;
import com.lzj.rag.ai.model.enums.ModelCapability;
import com.lzj.rag.ai.model.service.bo.ChatBO;
import com.lzj.rag.ai.model.service.bo.ModelChunkBO;
import com.lzj.rag.ai.model.service.bo.ToolCallBO;
import com.lzj.rag.ai.model.service.bo.UsageBO;

import java.util.List;
import java.util.function.Function;

/** 模型业务与供应商 DTO 之间的纯转换，不查询路由，也不发起请求。 */
public final class ModelProviderConverter {

    /** 工具类不需要实例，所有转换都通过静态方法完成。 */
    private ModelProviderConverter() {}

    /**
     * 把模型与供应商配置映射为客户端调用快照，不在转换器里决定候选路由。
     *
     * @param alias 应用内部的逻辑模型别名，不是供应商原生模型名。
     * @param model 本次操作选中的模型配置，用于能力、维度、批次和输出限制。
     * @param provider 本次模型所引用的供应商连接配置。
     * @return 只包含客户端所需字段的模型快照。
     */
    public static ProviderModelDTO toProvider(
            String alias, AiProperties.Model model, AiProperties.Provider provider) {
        return new ProviderModelDTO(
                alias,
                model.provider(),
                provider.kind(),
                model.model(),
                provider.baseUrl(),
                provider.apiKey(),
                provider.rerankUrl(),
                model.maxOutputTokens(),
                model.capabilities().contains(ModelCapability.THINKING),
                !model.allowedDimensions().isEmpty());
    }

    /**
     * 把业务消息、图片和工具定义转换为供应商客户端输入。
     *
     * @param input 当前待转换或发送的输入数据。
     * @return 不包含任务、档位和首选模型等路由信息的技术请求。
     */
    public static ProviderChatRequestDTO toProvider(ChatBO input) {
        // tool：当前工具定义或工具调用业务数据。
        // message：当前有序对话消息，包含角色、文本及可选图片或工具续接。
        // image：当前消息的图片引用，保留 URL、Base64 和媒体类型，不在转换时下载或解码。
        return new ProviderChatRequestDTO(
                input.text(),
                map(
                        input.messages(),
                        message ->
                                new ProviderChatRequestDTO.MessageDTO(
                                        message.role(),
                                        message.content(),
                                        map(
                                                message.images(),
                                                image ->
                                                        new ProviderChatRequestDTO.ImageDTO(
                                                                image.url(),
                                                                image.base64(),
                                                                image.mediaType())),
                                        message.toolCallId(),
                                        map(
                                                message.toolCalls(),
                                                ModelProviderConverter::toProvider),
                                        message.reasoning())),
                input.thinking(),
                input.json(),
                input.maxOutputTokens(),
                map(
                        input.tools(),
                        tool ->
                                new ProviderChatRequestDTO.ToolDTO(
                                        tool.name(), tool.description(), tool.parameters())),
                input.toolChoice());
    }

    /**
     * 将业务工具调用转换为供应商协议对象。
     *
     * @param input 当前待转换或发送的输入数据。
     * @return 字段含义保持一致的工具调用 DTO。
     */
    private static ProviderToolCallDTO toProvider(ToolCallBO input) {
        return new ProviderToolCallDTO(input.id(), input.name(), input.arguments(), input.index());
    }

    /**
     * 将供应商工具增量转换为业务数据，保留可缺失字段。
     *
     * @param input 需要转换为业务数据的供应商 DTO；其中未报告的字段保持原样。
     * @return 业务工具调用，输入为 null 时返回 null。
     */
    public static ToolCallBO toBO(ProviderToolCallDTO input) {
        return input == null
                ? null
                : new ToolCallBO(input.id(), input.name(), input.arguments(), input.index());
    }

    /**
     * 转换供应商真实用量，保持未知计数为 null。
     *
     * @param input 需要转换为业务数据的供应商 DTO；其中未报告的字段保持原样。
     * @return 业务用量，输入为 null 时返回 null。
     */
    public static UsageBO toBO(ProviderUsageDTO input) {
        return input == null
                ? null
                : new UsageBO(input.inputTokens(), input.outputTokens(), input.totalTokens());
    }

    /**
     * 把技术流片段转换成可参与业务状态判断的片段。
     *
     * @param input 需要转换为业务数据的供应商 DTO；其中未报告的字段保持原样。
     * @return 类型、文本、工具、用量与完成原因对应的业务片段。
     */
    public static ModelChunkBO toBO(ProviderChunkDTO input) {
        return new ModelChunkBO(
                input.type(),
                input.text(),
                toBO(input.tool()),
                toBO(input.usage()),
                input.finishReason());
    }

    /**
     * 逐项执行纯转换，保留空列表引用及空元素，让业务校验保留原始输入语义。
     *
     * @param values 当前处理的有序数值或待转换元素集合。
     * @param convert 对单个元素执行的纯数据转换函数。
     * @param <T> 当前业务结果或输入元素的类型。
     * @param <R> 转换后的元素类型。
     * @return 转换后的有序列表；原列表为 null 时仍为 null。
     */
    private static <T, R> List<R> map(List<T> values, Function<T, R> convert) {
        // value：当前待检查或转换的单个值。
        return values == null
                ? null
                : values.stream()
                        .map(value -> value == null ? null : convert.apply(value))
                        .toList();
    }
}
