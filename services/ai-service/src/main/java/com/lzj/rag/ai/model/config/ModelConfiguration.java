package com.lzj.rag.ai.model.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lzj.rag.ai.infra.provider.ProviderClient;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 模型业务的装配配置，将业务配置中的技术参数传给供应商客户端。 */
@Configuration(proxyBeanMethods = false)
public class ModelConfiguration {

    /**
     * 按应用配置创建供应商客户端，不让基础设施反向读取模型注册器。
     *
     * @param properties 已绑定的 AI 业务配置。
     * @param json JSON 对象解析器与构造器。
     * @return 由 Spring 管理的供应商客户端。
     */
    @Bean
    public ProviderClient providerClient(AiProperties properties, ObjectMapper json) {
        // 本次操作应遵守的资源与时间限制。
        var limits = properties.limits();
        return new ProviderClient(json, limits.connectTimeout(), limits.maxResponseBytes());
    }
}
