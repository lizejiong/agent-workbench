package com.lzj.rag.backend.document.controller.request;

import jakarta.validation.constraints.*;

/**
 * 切片策略及其目标长度、重叠量和表格行数参数。
 *
 * @param kind 当前结构块的类别或业务操作分类。
 * @param maxChars 当前文本或切片允许的字符数上限。
 * @param overlapChars 相邻切片需要保留的重叠字符数量。
 * @param tableRows 每个表格切片允许组合的数据行数量。
 * @param toleranceChars 切片目标长度允许的结构边界容差。
 */
public record SplitterRequest(
        @Pattern(regexp = "STRUCTURAL|WHOLE_DOCUMENT") String kind,
        @Min(128) @Max(4000) Integer maxChars,
        @Min(0) @Max(1000) Integer overlapChars,
        @Min(1) @Max(1000) Integer tableRows,
        @Min(0) @Max(4096) Integer toleranceChars) {

    /**
     * 初始化当前组件，保存业务依赖及本次使用的配置。
     *
     * @param kind 当前结构块的类别或业务操作分类。
     * @param maxChars 当前文本或切片允许的字符数上限。
     * @param overlapChars 相邻切片需要保留的重叠字符数量。
     */
    public SplitterRequest(String kind, Integer maxChars, Integer overlapChars) {
        this(kind, maxChars, overlapChars, null, null);
    }

    /** 校验并补齐缺省配置，构造可直接使用的配置快照。 */
    public SplitterRequest {
        if (kind == null) kind = "STRUCTURAL";
        if (maxChars == null) maxChars = 1024;
        if (overlapChars == null) overlapChars = 128;
        if (tableRows == null) tableRows = 50;
        if (toleranceChars == null) toleranceChars = 512;
    }

    /**
     * 校验切片重叠长度与目标长度的关系。
     *
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @AssertTrue(message = "overlapChars must be less than maxChars")
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isOverlapValid() {
        return overlapChars < maxChars;
    }
}
