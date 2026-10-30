package com.lzj.rag.backend.model.service;

import com.lzj.rag.backend.model.service.bo.EmbeddingProfileBO;

import java.util.List;

/** 查询允许被知识库绑定的嵌入模型配置。 */
public interface EmbeddingModelService {

    /**
     * @return 按公开配置标识排序的可用模型配置。
     */
    List<EmbeddingProfileBO> available();
}
