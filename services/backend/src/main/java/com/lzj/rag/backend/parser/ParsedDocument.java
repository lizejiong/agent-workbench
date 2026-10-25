package com.lzj.rag.backend.parser;

import java.util.List;
import java.util.Map;

public record ParsedDocument(String text, List<Block> blocks, String parserVersion) {
    public enum BlockType { HEADING, PARAGRAPH, LIST, CODE, TABLE }
    public record Block(int index, BlockType blockType, String content, List<String> chapterPath,
                        Map<String,Object> source, List<String> tableHeader, List<List<String>> tableRows) { }
}
