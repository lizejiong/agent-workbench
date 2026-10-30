package com.lzj.rag.backend.model.converter;

import com.lzj.rag.backend.model.controller.vo.EmbeddingProfileVO;
import com.lzj.rag.backend.model.service.bo.EmbeddingProfileBO;

/** 模型目录业务结果到 HTTP 响应的纯字段转换。 */
public final class ModelWebConverter {

    /** 禁止实例化无状态转换工具。 */
    private ModelWebConverter() {}

    /**
     * 转换公开模型目录项，保留模型身份和配置修订。
     *
     * @param value 已校验的公开嵌入模型配置。
     * @return 面向接口调用方的模型目录响应。
     */
    public static EmbeddingProfileVO toVO(EmbeddingProfileBO value) {
        return new EmbeddingProfileVO(
                value.id(),
                value.modelId(),
                value.provider(),
                value.modelName(),
                value.dimension(),
                value.embeddingSpace(),
                value.configVersion(),
                value.configRevision());
    }
}
