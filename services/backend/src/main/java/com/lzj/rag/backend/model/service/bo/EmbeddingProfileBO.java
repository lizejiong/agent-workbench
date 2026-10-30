package com.lzj.rag.backend.model.service.bo;

import java.util.*;

/**
 * 模型目录的公开配置项，不包含连接凭据。
 *
 * @param id 当前业务对象的唯一标识。
 * @param modelId 模型逻辑标识。
 * @param provider 模型供应商标识。
 * @param modelName 供应商原生模型名称。
 * @param dimension 向量维度。
 * @param embeddingSpace 嵌入向量空间身份，模型名称相同也不能跨空间混用。
 * @param configVersion 嵌入模型声明的配置版本。
 * @param configRevision 业务处理配置修订，用于识别任务输入是否已失效。
 */
public record EmbeddingProfileBO(
        String id,
        String modelId,
        String provider,
        String modelName,
        int dimension,
        String embeddingSpace,
        String configVersion,
        String configRevision) {}
