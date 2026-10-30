package com.lzj.rag.backend.parser.strategy;

import com.lzj.rag.backend.parser.enums.BlockType;
import com.lzj.rag.backend.parser.service.bo.ParsedBlockBO;
import com.lzj.rag.backend.parser.service.bo.ParsedDocumentBO;

import org.commonmark.ext.gfm.tables.*;
import org.commonmark.node.*;
import org.commonmark.parser.*;
import org.springframework.stereotype.Component;

import java.util.*;

/** 解析 Markdown 语法树，保留标题路径、代码和表格的结构与原文位置。 */
@Component
public class MarkdownDocumentParser implements DocumentParser {

    /** 当前选择的解析策略或解析器标识。 */
    private final Parser parser =
            Parser.builder()
                    .extensions(List.of(TablesExtension.create()))
                    .includeSourceSpans(IncludeSourceSpans.BLOCKS)
                    .build();

    /**
     * 返回解析器的稳定策略标识。
     *
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public String profile() {
        return "MARKDOWN";
    }

    /**
     * 返回当前解析策略支持的 MIME 类型集合。
     *
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public Set<String> mimeTypes() {
        return Set.of("text/plain", "text/markdown", "text/x-web-markdown");
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
        // headings：当前标题层级到标题文本的映射或标题文本集合。
        var headings = new TreeMap<Integer, String>();
        walk(parser.parse(text), headings, blocks, text.split("\n", -1));
        return new ParsedDocumentBO(text, List.copyOf(blocks), "commonmark-0.27.1-gfm-v1");
    }

    /**
     * 递归访问 Markdown 节点，维护章节路径并按节点类型生成内容块。
     *
     * @param parent 当前语法节点的父节点或解析子进程的父进程标识。
     * @param headings 当前标题层级到标题文本的映射或标题文本集合。
     * @param blocks 按源文档顺序排列的结构块。
     * @param lines 原始文本按行分割后的内容。
     */
    private void walk(
            Node parent,
            TreeMap<Integer, String> headings,
            List<ParsedBlockBO> blocks,
            String[] lines) {
        // node：当前访问的语法树节点。
        for (Node node = parent.getFirstChild(); node != null; node = node.getNext()) {
            // type：当前内容或数据库字段的类型标识。
            BlockType type;
            // content：用于展示或维护的正文内容。
            String content;
            // source：当前结构块的来源字段或已校验临时源文件。
            var source = new LinkedHashMap<String, Object>();
            // header：当前表格的列标题集合。
            List<String> header = List.of();
            // rows：数据库结果或当前表格数据行集合。
            List<List<String>> rows = List.of();
            // heading：当前 Markdown 标题节点。
            if (node instanceof Heading heading) {
                headings.tailMap(heading.getLevel()).clear();
                content = inline(node);
                headings.put(heading.getLevel(), content);
                type = BlockType.HEADING;
            } else if (node instanceof TableBlock) {
                type = BlockType.TABLE;
                // all：当前步骤收集的全部内容或是否选择全部切片。
                var all = new ArrayList<List<String>>();
                collectRows(node, all);
                if (all.isEmpty()) continue;
                header = all.get(0);
                rows = List.copyOf(all.subList(1, all.size()));
                content = table(header, rows);
            } else /*
                    * code：当前业务错误码或待保留原文格式的代码节点。
                    */ if (node instanceof FencedCodeBlock code) {
                type = BlockType.CODE;
                content = code.getLiteral().stripTrailing();
                source.put("language", code.getInfo());
            } else /*
                    * code：当前业务错误码或待保留原文格式的代码节点。
                    */ if (node instanceof IndentedCodeBlock code) {
                type = BlockType.CODE;
                content = code.getLiteral().stripTrailing();
            } else if (node instanceof ListBlock) {
                type = BlockType.LIST;
                content = original(node, lines);
            } else if (node instanceof Paragraph) {
                type = BlockType.PARAGRAPH;
                content = inline(node);
            } else {
                walk(node, headings, blocks, lines);
                continue;
            }
            if (content.isBlank()) continue;
            // spans：需要延续到后续行的跨行单元格状态。
            var spans = node.getSourceSpans();
            if (!spans.isEmpty()) {
                source.put("lineStart", spans.get(0).getLineIndex() + 1);
                source.put("lineEnd", spans.get(spans.size() - 1).getLineIndex() + 1);
            }
            blocks.add(
                    new ParsedBlockBO(
                            blocks.size(),
                            type,
                            content,
                            List.copyOf(headings.values()),
                            Map.copyOf(source),
                            header,
                            rows));
        }
    }

    /**
     * 从 Markdown 表格节点收集单元格文本。
     *
     * @param node 当前访问的语法树节点。
     * @param all 当前步骤收集的全部内容或是否选择全部切片。
     */
    private static void collectRows(Node node, List<List<String>> all) {
        if (node instanceof TableRow) {
            // row：当前数据库记录或表格数据行。
            var row = new ArrayList<String>();
            // cell：当前表格单元格或其文本缓冲。
            for (Node cell = node.getFirstChild(); cell != null; cell = cell.getNext())
                row.add(inline(cell));
            all.add(List.copyOf(row));
            return;
        }
        // child：当前节点的子节点。
        for (Node child = node.getFirstChild(); child != null; child = child.getNext())
            collectRows(child, all);
    }

    /**
     * 按源码位置提取原始片段，保留代码块等内容的原文格式。
     *
     * @param node 当前访问的语法树节点。
     * @param lines 原始文本按行分割后的内容。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private static String original(Node node, String[] lines) {
        // spans：需要延续到后续行的跨行单元格状态。
        var spans = node.getSourceSpans();
        if (spans.isEmpty()) return inline(node);
        // first：当前处理范围的起始位置或首个有效内容。
        int first = spans.get(0).getLineIndex();
        // last：当前处理范围的结束位置。
        int last = spans.get(spans.size() - 1).getLineIndex();
        return String.join(
                "\n", Arrays.copyOfRange(lines, first, Math.min(last + 1, lines.length)));
    }

    /**
     * 将行内节点组合为纯文本。
     *
     * @param node 当前访问的语法树节点。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private static String inline(Node node) {
        // result：当前步骤产生的处理结果。
        var result = new StringBuilder();
        append(node, result);
        return result.toString().strip();
    }

    /**
     * 递归追加行内文本及换行，避免丢失节点内可见内容。
     *
     * @param node 当前访问的语法树节点。
     * @param result 逐步构造的文本缓冲区。
     */
    private static void append(Node node, StringBuilder result) {
        // text：当前待处理或生成的文本内容。
        if (node instanceof Text text) result.append(text.getLiteral());
        else /*
              * code：当前业务错误码或待保留原文格式的代码节点。
              */ if (node instanceof Code code) result.append(code.getLiteral());
        else if (node instanceof SoftLineBreak || node instanceof HardLineBreak)
            result.append('\n');
        else
            // child：当前节点的子节点。
            for (Node child = node.getFirstChild(); child != null; child = child.getNext())
                append(child, result);
    }

    /**
     * 将表头和数据行渲染为可读的 Markdown 表格。
     *
     * @param header 当前表格的列标题集合。
     * @param rows 数据库结果或当前表格数据行集合。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public static String table(List<String> header, List<List<String>> rows) {
        // first：当前处理范围的起始位置或首个有效内容。
        String first =
                "| "
                        + String.join(
                                " | ", header.stream().map(MarkdownDocumentParser::escape).toList())
                        + " |";
        // result：当前步骤产生的处理结果。
        var result =
                new StringBuilder(first)
                        .append('\n')
                        .append("| ")
                        .append(String.join(" | ", Collections.nCopies(header.size(), "---")))
                        .append(" |");
        // row：当前数据库记录或表格数据行。
        for (var row : rows)
            result.append('\n')
                    .append("| ")
                    .append(
                            String.join(
                                    " | ",
                                    row.stream().map(MarkdownDocumentParser::escape).toList()))
                    .append(" |");
        return result.toString();
    }

    /**
     * 转义单元格中的分隔符和换行，避免破坏 Markdown 表格结构。
     *
     * @param text 当前待处理或生成的文本内容。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("|", "\\|").replace("\n", "<br>");
    }
}
