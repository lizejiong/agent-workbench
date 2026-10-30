package com.lzj.rag.backend.document.service.bo;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

/**
 * 文档处理配置，版本化记录解析器、解析档位及切片策略。
 *
 * @param schemaVersion 文档处理配置的结构版本。
 * @param parser 当前选择的解析策略或解析器标识。
 * @param splitter 文档选择的切片策略及容量配置。
 * @param parseProfile 本次解析使用的快速或高保真档位。
 */
public record IngestionSpecBO(
        @Min(1) @Max(1) Integer schemaVersion,
        @Pattern(regexp = "AUTO|TEXT|MARKDOWN") String parser,
        @Valid SplitterBO splitter,
        @Pattern(regexp = "fast|fidelity") String parseProfile) {

    /**
     * 初始化当前组件，保存业务依赖及本次使用的配置。
     *
     * @param schemaVersion 文档处理配置的结构版本。
     * @param parser 当前选择的解析策略或解析器标识。
     * @param splitter 文档选择的切片策略及容量配置。
     */
    public IngestionSpecBO(Integer schemaVersion, String parser, SplitterBO splitter) {
        this(schemaVersion, parser, splitter, null);
    }

    /** 校验并补齐缺省配置，构造可直接使用的配置快照。 */
    public IngestionSpecBO {
        if (schemaVersion == null) schemaVersion = 1;
        if (parser == null) parser = "AUTO";
        if (splitter == null) splitter = new SplitterBO(null, null, null);
        if (parseProfile == null) parseProfile = "fast";
    }

    /**
     * 构造默认文档处理配置。
     *
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public static IngestionSpecBO defaults() {
        return new IngestionSpecBO(1, "AUTO", null);
    }
}
