package com.lzj.rag.backend.model.service.impl;

import com.lzj.rag.backend.model.catalog.EmbeddingCatalog;
import com.lzj.rag.backend.model.service.EmbeddingModelService;
import com.lzj.rag.backend.model.service.bo.EmbeddingProfileBO;

import org.springframework.stereotype.Service;

import java.util.List;

/** 将已校验模型目录作为可复用的业务查询能力提供给各入口。 */
@Service
public class EmbeddingModelServiceImpl implements EmbeddingModelService {

    /** 已完成启动校验的嵌入模型目录。 */
    private final EmbeddingCatalog catalog;

    /**
     * 注入统一的嵌入模型目录。
     *
     * @param catalog 保存公开配置及稳定身份修订的模型目录。
     */
    public EmbeddingModelServiceImpl(EmbeddingCatalog catalog) {
        this.catalog = catalog;
    }

    /**
     * @return 按公开配置标识排序的可用嵌入模型配置。
     */
    @Override
    public List<EmbeddingProfileBO> available() {
        return catalog.available();
    }
}
