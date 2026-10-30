package com.lzj.rag.backend.model.service.bo;

/**
 * 一次嵌入调用固定的模型身份，同时携带本地及可选 AI 配置修订。
 *
 * @param modelId 模型逻辑标识。
 * @param provider 模型供应商标识。
 * @param modelName 供应商原生模型名称。
 * @param dimension 向量维度。
 * @param embeddingSpace 嵌入向量空间身份，模型名称相同也不能跨空间混用。
 * @param configVersion 嵌入模型声明的配置版本。
 * @param localRevision 后端嵌入配置修订摘要。
 * @param expectedAiRevision 请求要求匹配的 AI 配置修订；未固定时为空。
 */
public record EmbeddingBindingBO(
        String modelId,
        String provider,
        String modelName,
        int dimension,
        String embeddingSpace,
        String configVersion,
        String localRevision,
        String expectedAiRevision) {

    /**
     * 初始化当前组件，保存业务依赖及本次使用的配置。
     *
     * @param modelId 模型逻辑标识。
     * @param provider 模型供应商标识。
     * @param modelName 供应商原生模型名称。
     * @param dimension 向量维度。
     * @param embeddingSpace 嵌入向量空间身份，模型名称相同也不能跨空间混用。
     * @param configVersion 嵌入模型声明的配置版本。
     * @param localRevision 后端嵌入配置修订摘要。
     */
    public EmbeddingBindingBO(
            String modelId,
            String provider,
            String modelName,
            int dimension,
            String embeddingSpace,
            String configVersion,
            String localRevision) {
        this(
                modelId,
                provider,
                modelName,
                dimension,
                embeddingSpace,
                configVersion,
                localRevision,
                null);
    }
}
