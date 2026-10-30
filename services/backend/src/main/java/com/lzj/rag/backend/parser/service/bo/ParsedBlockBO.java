package com.lzj.rag.backend.parser.service.bo;

import com.lzj.rag.backend.parser.enums.BlockType;

import java.util.List;
import java.util.Map;

/**
 * 带标题路径和来源坐标的结构块，表格另存表头及数据行。
 *
 * @param index 当前元素位置或关键词索引名称。
 * @param blockType 结构块类别。
 * @param content 用于展示或维护的正文内容。
 * @param chapterPath 结构块或切片所属的章节路径。
 * @param source 当前结构块的来源字段或已校验临时源文件。
 * @param tableHeader 当前表格已提取的列标题。
 * @param tableRows 表格切片行数配置或当前表格已收集的数据行。
 */
public record ParsedBlockBO(
        int index,
        BlockType blockType,
        String content,
        List<String> chapterPath,
        Map<String, Object> source,
        List<String> tableHeader,
        List<List<String>> tableRows) {}
