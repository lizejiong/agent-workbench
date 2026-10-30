package com.lzj.rag.ai.infra.provider.dto;

import com.lzj.rag.ai.infra.provider.enums.ProviderKind;

/**
 * 一次供应商调用所需的模型与连接快照，包含凭据，禁止输出到日志或接口。
 *
 * @param alias 应用内部的逻辑模型别名，不是供应商原生模型名。
 * @param provider 供应商配置标识，用于关联 ai.providers 中的连接配置。
 * @param kind 供应商原生协议类型。
 * @param model 供应商原生模型名称，实际发送到供应商接口的 model 字段。
 * @param baseUrl 供应商兼容模式 API 根地址，由客户端追加具体接口路径。
 * @param apiKey 供应商访问密钥，禁止出现在日志和对外响应中。
 * @param rerankUrl 供应商原生重排接口的完整 URL，不再追加路径。
 * @param maxOutputTokens 期望或配置允许的输出 Token 上限，实际调用取请求与模型上限的较小值。
 * @param thinking 该模型是否声明思考能力，决定客户端是否发送原生思考开关。
 * @param adjustableDimensions 是否应向供应商显式发送 dimensions；固定维度模型不发送该字段。
 */
public record ProviderModelDTO(
        String alias,
        String provider,
        ProviderKind kind,
        String model,
        String baseUrl,
        String apiKey,
        String rerankUrl,
        int maxOutputTokens,
        boolean thinking,
        boolean adjustableDimensions) {}
