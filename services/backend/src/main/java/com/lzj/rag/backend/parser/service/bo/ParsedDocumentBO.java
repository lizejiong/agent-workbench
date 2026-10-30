package com.lzj.rag.backend.parser.service.bo;

import java.util.List;

/**
 * 统一解析结果，包含解析器版本、格式类型及按顺序排列的结构块。
 *
 * @param text 当前待处理或生成的文本内容。
 * @param blocks 按源文档顺序排列的结构块。
 * @param parserVersion 本次解析器使用的版本标记。
 */
public record ParsedDocumentBO(String text, List<ParsedBlockBO> blocks, String parserVersion) {}
