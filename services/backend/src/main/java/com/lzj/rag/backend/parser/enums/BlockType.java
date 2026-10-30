package com.lzj.rag.backend.parser.enums;

/** 结构块类别，切片时按类别保留段落、代码或表格边界。 */
public enum BlockType {
    // 标题结构块。
    HEADING,
    // 普通段落结构块。
    PARAGRAPH,
    // 列表结构块。
    LIST,
    // 代码结构块。
    CODE,
    // 表格结构块。
    TABLE
}
