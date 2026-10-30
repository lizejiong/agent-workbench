package com.lzj.rag.backend.document.controller.vo;

/**
 * 切片策略及其目标长度、重叠量和表格行数参数。
 *
 * @param kind 当前结构块的类别或业务操作分类。
 * @param maxChars 当前文本或切片允许的字符数上限。
 * @param overlapChars 相邻切片需要保留的重叠字符数量。
 * @param tableRows 每个表格切片允许组合的数据行数量。
 * @param toleranceChars 切片目标长度允许的结构边界容差。
 */
public record SplitterVO(
        String kind,
        Integer maxChars,
        Integer overlapChars,
        Integer tableRows,
        Integer toleranceChars) {}
