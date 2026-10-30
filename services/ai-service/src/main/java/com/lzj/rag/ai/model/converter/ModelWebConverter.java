package com.lzj.rag.ai.model.converter;

import com.lzj.rag.ai.model.controller.request.ChatRequest;
import com.lzj.rag.ai.model.controller.request.EmbedRequest;
import com.lzj.rag.ai.model.controller.request.RerankRequest;
import com.lzj.rag.ai.model.controller.vo.*;
import com.lzj.rag.ai.model.service.bo.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** HTTP Request、业务 BO 和返回 VO 之间的纯转换。 */
public final class ModelWebConverter {

    /** 工具类不需要实例，所有转换都通过静态方法完成。 */
    private ModelWebConverter() {}

    /**
     * 将对话请求转换为业务输入，保留消息、图片和工具的原始顺序。
     *
     * @param request 当前操作的输入请求数据。
     * @return 与业务数据对应的响应或输入对象，字段值不做业务计算。
     */
    public static ChatBO toBO(ChatRequest request) {
        // tool：当前工具定义或工具调用业务数据。
        // message：当前有序对话消息，包含角色、文本及可选图片或工具续接。
        // image：当前消息的图片输入；call：当前续接消息包含的工具调用，转换时保留关联 ID 和索引。
        return new ChatBO(
                request.text(),
                map(
                        request.messages(),
                        message ->
                                new ChatBO.MessageBO(
                                        message.role(),
                                        message.content(),
                                        map(
                                                message.images(),
                                                image ->
                                                        new ChatBO.ImageBO(
                                                                image.url(),
                                                                image.base64(),
                                                                image.mediaType())),
                                        message.toolCallId(),
                                        map(
                                                message.toolCalls(),
                                                call ->
                                                        new ToolCallBO(
                                                                call.id(),
                                                                call.name(),
                                                                call.arguments(),
                                                                call.index())),
                                        message.reasoning())),
                request.task(),
                request.tier(),
                request.preferredModel(),
                request.thinking(),
                request.json(),
                request.maxOutputTokens(),
                map(
                        request.tools(),
                        tool ->
                                new ChatBO.ToolBO(
                                        tool.name(), tool.description(), tool.parameters())),
                request.toolChoice());
    }

    /**
     * 将向量请求转换为业务输入，保留模型和配置修订约束。
     *
     * @param request 当前操作的输入请求数据。
     * @return 与业务数据对应的响应或输入对象，字段值不做业务计算。
     */
    public static EmbedBO toBO(EmbedRequest request) {
        return new EmbedBO(
                request.text(),
                request.texts(),
                request.model(),
                request.dimensions(),
                request.expectedConfigRevision());
    }

    /**
     * 将重排请求转换为业务输入，保留文档 ID 和原文。
     *
     * @param request 当前操作的输入请求数据。
     * @return 与业务数据对应的响应或输入对象，字段值不做业务计算。
     */
    public static RerankBO toBO(RerankRequest request) {
        // document：当前待转换的重排文档，保留其业务 ID 与文本。
        return new RerankBO(
                request.query(),
                map(
                        request.documents(),
                        document -> new RerankBO.DocumentBO(document.id(), document.text())),
                request.topN(),
                request.model());
    }

    /**
     * 将对话业务结果转换为响应对象，包括工具调用和可选用量。
     *
     * @param result 当前业务处理或对象转换得到的结果。
     * @return 与业务数据对应的响应或输入对象，字段值不做业务计算。
     */
    public static ChatVO toVO(ChatResultBO result) {
        return new ChatVO(
                result.content(),
                result.reasoning(),
                map(result.toolCalls(), ModelWebConverter::toVO),
                result.model(),
                result.finishReason(),
                toVO(result.usage()),
                toVO(result.metrics()));
    }

    /**
     * 将完整向量结果转换为响应对象，保留输入索引和实际模型身份。
     *
     * @param result 当前业务处理或对象转换得到的结果。
     * @return 与业务数据对应的响应或输入对象，字段值不做业务计算。
     */
    public static EmbedVO toVO(EmbedResultBO result) {
        // vector：当前批次中待校验或转换的一个向量。
        return new EmbedVO(
                map(
                        result.embeddings(),
                        vector -> new EmbedVO.VectorVO(vector.index(), vector.values())),
                result.dimensions(),
                result.model(),
                toVO(result.usage()),
                toVO(result.metrics()),
                result.provider(),
                result.embeddingSpace(),
                result.configRevision());
    }

    /**
     * 将业务排序结果转换为文档重排响应。
     *
     * @param result 当前业务处理或对象转换得到的结果。
     * @return 与业务数据对应的响应或输入对象，字段值不做业务计算。
     */
    public static RerankVO toVO(RerankResultBO result) {
        // ranked：已经保留文档身份的单项重排业务结果。
        return new RerankVO(
                map(
                        result.results(),
                        ranked ->
                                new RerankVO.RankedVO(
                                        ranked.chunkId(), ranked.index(), ranked.score())),
                result.model(),
                toVO(result.usage()),
                toVO(result.metrics()));
    }

    /**
     * 把可公开的模型元数据转换为响应对象。
     *
     * @param result 当前业务处理或对象转换得到的结果。
     * @return 与业务数据对应的响应或输入对象，字段值不做业务计算。
     */
    public static EmbeddingModelVO toVO(EmbeddingModelBO result) {
        return new EmbeddingModelVO(
                result.modelId(),
                result.provider(),
                result.providerKind(),
                result.modelName(),
                result.dimensions(),
                result.embeddingSpace(),
                result.configVersion(),
                result.configRevision(),
                result.batchSize(),
                result.active(),
                result.protocolVersion());
    }

    /**
     * 将业务流事件的数据部分转为响应对象，事件类型由 Controller 单独封装。
     *
     * @param event 当前收到或即将输出的模型流事件。
     * @return 与业务数据对应的响应或输入对象，字段值不做业务计算。
     */
    public static ModelEventVO toVO(ModelEventBO event) {
        return new ModelEventVO(event.requestId(), event.model(), payload(event.payload()));
    }

    /**
     * 按载荷实际类型转换工具、用量和统计对象，递归处理 done 等事件的嵌套映射。
     *
     * @param value 当前待检查或转换的单个值。
     * @return 只包含响应对象和原始简单值的事件载荷。
     */
    private static Object payload(Object value) {
        // tool：待转换的工具调用载荷。
        if (value instanceof ToolCallBO tool) return toVO(tool);
        // usage：待转换的真实用量载荷。
        if (value instanceof UsageBO usage) return toVO(usage);
        // metrics：待转换的统计载荷。
        if (value instanceof MetricsBO metrics) return toVO(metrics);
        // values：结束等事件中需要递归转换的映射载荷。
        if (value instanceof Map<?, ?> values) {
            // 当前业务处理或对象转换得到的结果。
            Map<String, Object> result = new LinkedHashMap<>();
            // key：当前需要读取或写入的字段名。
            // item：事件映射中待递归转换的单个载荷值。
            values.forEach((key, item) -> result.put((String) key, payload(item)));
            return result;
        }
        return value;
    }

    /**
     * 转换工具调用的完整或增量字段。
     *
     * @param tool 当前工具定义或工具调用业务数据。
     * @return 与业务数据对应的响应或输入对象，字段值不做业务计算。
     */
    private static ChatVO.ToolCallVO toVO(ToolCallBO tool) {
        return new ChatVO.ToolCallVO(tool.id(), tool.name(), tool.arguments(), tool.index());
    }

    /**
     * 转换真实用量，保留整个用量对象或计数字段的未知状态。
     *
     * @param usage 供应商报告的真实用量；缺失时保持 null。
     * @return 与业务数据对应的响应或输入对象，字段值不做业务计算。
     */
    private static UsageVO toVO(UsageBO usage) {
        return usage == null
                ? null
                : new UsageVO(usage.inputTokens(), usage.outputTokens(), usage.totalTokens());
    }

    /**
     * 转换逻辑请求的统计快照。
     *
     * @param metrics 一次逻辑请求的尝试次数和耗时统计。
     * @return 与业务数据对应的响应或输入对象，字段值不做业务计算。
     */
    private static MetricsVO toVO(MetricsBO metrics) {
        return new MetricsVO(metrics.attempts(), metrics.durationMs(), metrics.firstTokenMs());
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
