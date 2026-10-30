package com.lzj.rag.ai.infra.provider.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * 发送给供应商适配器的对话参数，不携带业务路由规则。
 *
 * @param text 单轮快捷输入，与完整 messages 列表必须二选一。
 * @param messages 按原有对话顺序组织的消息集合。
 * @param thinking 是否开启模型原生思考模式，未开启时也会显式关闭供应商默认思考。
 * @param json 是否要求供应商返回原生 JSON 对象，返回结果也必须通过完整对象校验。
 * @param maxOutputTokens 期望或配置允许的输出 Token 上限，实际调用取请求与模型上限的较小值。
 * @param tools 当前操作声明的工具定义集合。
 * @param toolChoice 供应商原生工具选择策略，仅在同时提供工具定义时有效。
 */
public record ProviderChatRequestDTO(
        String text,
        List<MessageDTO> messages,
        Boolean thinking,
        Boolean json,
        Integer maxOutputTokens,
        List<ToolDTO> tools,
        JsonNode toolChoice) {

    /**
     * 供应商技术消息，支持文本、图片、工具续接与独立推理内容。
     *
     * @param role 消息角色，仅允许 system、user、assistant 或 tool。
     * @param content 当前消息的正文，图片消息或工具调用消息可按约定缺省。
     * @param images 消息中按原始顺序提供的图片引用集合。
     * @param toolCallId 工具结果所对应的调用 ID，用于与先前的 assistant 工具调用关联。
     * @param toolCalls 当前消息或结果包含的工具调用列表，流式场景按 index 拼接。
     * @param reasoning 模型独立返回的推理内容，也可用于工具回合续接。
     */
    public record MessageDTO(
            String role,
            String content,
            List<ImageDTO> images,
            String toolCallId,
            List<ProviderToolCallDTO> toolCalls,
            String reasoning) {}

    /**
     * 供应商技术图片引用，URL 与 Base64 内容必须二选一。
     *
     * @param url 图片 HTTP(S) URL，与 Base64 内容二选一；本服务不下载图片。
     * @param base64 不含 data URL 前缀的图片 Base64 编码内容。
     * @param mediaType Base64 图片的 MIME 类型，只接受配置约定的图片格式。
     */
    public record ImageDTO(String url, String base64, String mediaType) {}

    /**
     * 供应商技术工具定义；这里只描述工具，服务不会实际执行工具。
     *
     * @param name 工具函数名称或当前具名配置的标识。
     * @param description 提供给模型的工具用途说明，可缺省。
     * @param parameters 工具参数的 JSON Schema 对象，服务只转发定义，不执行工具。
     */
    public record ToolDTO(String name, String description, JsonNode parameters) {}
}
