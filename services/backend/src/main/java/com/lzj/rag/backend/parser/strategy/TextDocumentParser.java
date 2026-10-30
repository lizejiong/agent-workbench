package com.lzj.rag.backend.parser.strategy;

import com.lzj.rag.backend.parser.enums.BlockType;
import com.lzj.rag.backend.parser.service.bo.ParsedBlockBO;
import com.lzj.rag.backend.parser.service.bo.ParsedDocumentBO;

import org.springframework.stereotype.Component;

import java.util.*;

/** 将纯文本整理为可切片的结构块。 */
@Component
public class TextDocumentParser implements DocumentParser {

    /**
     * 返回解析器的稳定策略标识。
     *
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public String profile() {
        return "TEXT";
    }

    /**
     * 返回当前解析策略支持的 MIME 类型集合。
     *
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public Set<String> mimeTypes() {
        return Set.of("text/plain");
    }

    /**
     * 按当前解析策略读取输入，在容量与时间限制内生成统一结构块。
     *
     * @param text 当前待处理或生成的文本内容。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public ParsedDocumentBO parse(String text) {
        // blocks：按源文档顺序排列的结构块。
        var blocks = new ArrayList<ParsedBlockBO>();
        // lines：原始文本按行分割后的内容。
        String[] lines = text.split("\n", -1);
        // paragraph：当前待处理的段落结构块。
        var paragraph = new StringBuilder();
        // start：当前片段或表格分组的起始位置。
        int start = 1;
        // i：当前遍历位置，按原始输入顺序递增。
        for (int i = 0; i <= lines.length; i++) {
            // line：当前处理的完整文本行。
            String line = i == lines.length ? "" : lines[i];
            if (line.isBlank()) {
                if (!paragraph.isEmpty()) {
                    blocks.add(
                            new ParsedBlockBO(
                                    blocks.size(),
                                    BlockType.PARAGRAPH,
                                    paragraph.toString().strip(),
                                    List.of(),
                                    Map.of("lineStart", start, "lineEnd", i),
                                    List.of(),
                                    List.of()));
                    paragraph.setLength(0);
                }
            } else {
                if (paragraph.isEmpty()) start = i + 1;
                else paragraph.append('\n');
                paragraph.append(line);
            }
        }
        return new ParsedDocumentBO(text, List.copyOf(blocks), "utf8-paragraph-v1");
    }
}
