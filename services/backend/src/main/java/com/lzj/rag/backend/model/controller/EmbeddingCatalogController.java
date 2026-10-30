package com.lzj.rag.backend.model.controller;

import com.lzj.rag.backend.model.controller.vo.EmbeddingProfileVO;
import com.lzj.rag.backend.model.converter.ModelWebConverter;
import com.lzj.rag.backend.model.service.EmbeddingModelService;
import com.lzj.rag.framework.convention.result.Result;
import com.lzj.rag.framework.starter.web.result.Results;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 查询后端可绑定的嵌入模型目录。 */
@RestController
public class EmbeddingCatalogController {

    /** 已校验的嵌入配置目录。 */
    private final EmbeddingModelService catalog;

    /**
     * 初始化当前组件，保存业务依赖及本次使用的配置。
     *
     * @param catalog 已校验的嵌入配置目录。
     */
    public EmbeddingCatalogController(EmbeddingModelService catalog) {
        this.catalog = catalog;
    }

    /**
     * 返回可公开选择的嵌入配置目录。
     *
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @GetMapping("/api/kb-models")
    public Result<List<EmbeddingProfileVO>> list() {
        return Results.success(catalog.available().stream().map(ModelWebConverter::toVO).toList());
    }
}
