package com.lzj.rag.backend.chunk.splitting;

import com.lzj.rag.backend.chunk.service.bo.ChunkBO;
import com.lzj.rag.backend.document.service.bo.SplitterBO;
import com.lzj.rag.backend.ingestion.config.IngestionProperties;
import com.lzj.rag.backend.knowledgebase.exception.KnowledgeBaseException;
import com.lzj.rag.backend.model.catalog.EmbeddingCatalog;
import com.lzj.rag.backend.parser.enums.BlockType;
import com.lzj.rag.backend.parser.service.bo.ParsedBlockBO;
import com.lzj.rag.backend.parser.service.bo.ParsedDocumentBO;
import com.lzj.rag.backend.parser.strategy.MarkdownDocumentParser;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** 按段落、行或表格结构切片，同时控制展示正文和嵌入文本的大小。 */
@Service
@EnableConfigurationProperties(IngestionProperties.class)
public class ChunkingService {

    /** 入库流水线的时间预算、文本长度、切片数量和向量写入限制。 */
    private final IngestionProperties limits;

    /**
     * 初始化当前组件，保存业务依赖及本次使用的配置。
     *
     * @param limits 入库流水线的时间预算、文本长度、切片数量和向量写入限制。
     */
    public ChunkingService(IngestionProperties limits) {
        this.limits = limits;
    }

    /**
     * 校验切片配置，按结构类型生成满足容量限制且保留来源信息的切片。
     *
     * @param documentId 文档唯一标识。
     * @param generation 切片或任务所属的数据代次。
     * @param parsed 已解析的有序结构块及来源信息。
     * @param spec 文档入库配置或当前切片策略参数。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public List<ChunkBO> chunk(
            UUID documentId, long generation, ParsedDocumentBO parsed, SplitterBO spec) {
        if (spec.maxChars() > limits.hardChunkChars())
            throw invalid("Chunk target exceeds configured hard budget");
        // b：当前需要保留结构及来源信息的解析块。
        if (parsed.blocks().stream().noneMatch(b -> b.blockType() != BlockType.HEADING))
            throw empty();
        // output：按原文顺序累计生成的切片集合。
        var output = new ArrayList<ChunkBO>();
        if (spec.kind().equals("WHOLE_DOCUMENT")) {
            emit(
                    output,
                    documentId,
                    generation,
                    parsed.text().strip(),
                    parsed.text().strip(),
                    parsed.blocks(),
                    Map.of("wholeDocument", true),
                    parsed.parserVersion());
            return List.copyOf(output);
        }
        // blocks：按源文档顺序排列的结构块。
        var blocks = parsed.blocks();
        // i：当前结构块、文本行或表格列的遍历位置。
        for (int i = 0; i < blocks.size(); ) {
            // block：当前处理的结构块。
            ParsedBlockBO block = blocks.get(i);
            if (block.blockType() == BlockType.HEADING) {
                i++;
                continue;
            }
            if (block.blockType() == BlockType.PARAGRAPH) {
                // end：当前范围的结束位置或局部阶段截止点。
                int end = i + 1;
                while (end < blocks.size()
                        && blocks.get(end).blockType() == BlockType.PARAGRAPH
                        && blocks.get(end).index() == blocks.get(end - 1).index() + 1
                        && blocks.get(end).chapterPath().equals(block.chapterPath())
                        && Objects.equals(
                                blocks.get(end).source().get("pageNumber"),
                                block.source().get("pageNumber"))
                        && Objects.equals(
                                blocks.get(end).source().get("slideNumber"),
                                block.source().get("slideNumber"))) end++;
                paragraphs(
                        output,
                        documentId,
                        generation,
                        blocks.subList(i, end),
                        spec,
                        parsed.parserVersion());
                i = end;
            } else {
                if (block.blockType() == BlockType.TABLE)
                    table(output, documentId, generation, block, spec, parsed.parserVersion());
                else lines(output, documentId, generation, block, spec, parsed.parserVersion());
                i++;
            }
        }
        if (output.isEmpty()) throw empty();
        return List.copyOf(output);
    }

    /**
     * 在相同章节内组合段落，达到目标长度时输出并按配置保留重叠内容。
     *
     * @param out 当前结果集合或文件输出流。
     * @param doc 本次操作涉及的文档唯一标识。
     * @param generation 切片或任务所属的数据代次。
     * @param blocks 按源文档顺序排列的结构块。
     * @param spec 文档入库配置或当前切片策略参数。
     * @param parserVersion 本次解析器使用的版本标记。
     */
    private void paragraphs(
            List<ChunkBO> out,
            UUID doc,
            long generation,
            List<ParsedBlockBO> blocks,
            SplitterBO spec,
            String parserVersion) {
        // text：当前待处理或生成的文本内容。
        var text = new StringBuilder();
        // starts：源结构片段的起始偏移集合。
        var starts = new ArrayList<Integer>();
        // ends：源结构片段的结束偏移集合。
        var ends = new ArrayList<Integer>();
        // b：当前需要保留结构及来源信息的解析块。
        for (var b : blocks) {
            if (!text.isEmpty()) text.append("\n\n");
            starts.add(text.length());
            text.append(b.content());
            ends.add(text.length());
        }
        // value：当前待校验、转换或写入的值。
        String value = text.toString();
        // start：当前片段或表格分组的起始位置。
        int start = 0;
        while (start < value.length()) {
            // end：当前范围的结束位置或局部阶段截止点。
            int end =
                    value.offsetByCodePoints(
                            start,
                            Math.min(spec.maxChars(), value.codePointCount(start, value.length())));
            if (end < value.length()) {
                // boundary：目标切片附近的段落分隔位置，用于尽量保留完整结构。
                int boundary = value.lastIndexOf("\n\n", end);
                if (boundary < start) boundary = value.lastIndexOf(' ', end);
                if (boundary > start
                        && value.codePointCount(start, boundary)
                                > Math.max(spec.overlapChars(), spec.maxChars() / 2))
                    end = boundary;
            }
            // sources：当前切片引用的原始结构块集合。
            var sources = new ArrayList<ParsedBlockBO>();
            // i：当前结构块、文本行或表格列的遍历位置。
            for (int i = 0; i < blocks.size(); i++)
                if (starts.get(i) < end && ends.get(i) > start) sources.add(blocks.get(i));
            // content：用于展示或维护的正文内容。
            String content = value.substring(start, end).strip();
            if (!content.isBlank())
                emit(
                        out,
                        doc,
                        generation,
                        content,
                        content,
                        sources,
                        Map.of(
                                "textStart",
                                start,
                                "textEnd",
                                end,
                                "offsetUnit",
                                "UTF16",
                                "overlapChars",
                                start == 0 ? 0 : spec.overlapChars()),
                        parserVersion);
            if (end == value.length()) break;
            start =
                    value.offsetByCodePoints(
                            end,
                            -Math.min(spec.overlapChars(), value.codePointCount(start, end) - 1));
        }
    }

    /**
     * 按完整行组合代码或文本，避免在结构内部任意截断。
     *
     * @param out 当前结果集合或文件输出流。
     * @param doc 本次操作涉及的文档唯一标识。
     * @param generation 切片或任务所属的数据代次。
     * @param block 当前处理的结构块。
     * @param spec 文档入库配置或当前切片策略参数。
     * @param parserVersion 本次解析器使用的版本标记。
     */
    private void lines(
            List<ChunkBO> out,
            UUID doc,
            long generation,
            ParsedBlockBO block,
            SplitterBO spec,
            String parserVersion) {
        // soft：结构切片允许使用的目标长度加容差。
        int soft = Math.min(limits.hardChunkChars(), spec.maxChars() + spec.toleranceChars());
        if (chars(block.content()) <= soft) {
            emit(
                    out,
                    doc,
                    generation,
                    block.content(),
                    block.content(),
                    List.of(block),
                    Map.of(),
                    parserVersion);
            return;
        }
        // lines：原始文本按行分割后的内容。
        String[] lines = block.content().split("\n", -1);
        // first：当前处理范围的起始位置或首个有效内容。
        int first = 0;
        // pending：尚未输出为结构块或切片的文本缓冲。
        var pending = new StringBuilder();
        // i：当前结构块、文本行或表格列的遍历位置。
        for (int i = 0; i < lines.length; i++) {
            if (chars(lines[i]) > limits.hardChunkChars()) throw tooLarge();
            if (!pending.isEmpty()
                    && chars(pending.toString()) + 1 + chars(lines[i]) > spec.maxChars()) {
                emit(
                        out,
                        doc,
                        generation,
                        pending.toString(),
                        pending.toString(),
                        List.of(block),
                        Map.of("fragmentLineStart", first + 1, "fragmentLineEnd", i),
                        parserVersion);
                pending.setLength(0);
                first = i;
            }
            if (!pending.isEmpty()) pending.append('\n');
            pending.append(lines[i]);
        }
        if (!pending.isEmpty())
            emit(
                    out,
                    doc,
                    generation,
                    pending.toString(),
                    pending.toString(),
                    List.of(block),
                    Map.of("fragmentLineStart", first + 1, "fragmentLineEnd", lines.length),
                    parserVersion);
    }

    /**
     * 重复表头并按数据行分组，生成展示表格和用于嵌入的语义文本。
     *
     * @param out 当前结果集合或文件输出流。
     * @param doc 本次操作涉及的文档唯一标识。
     * @param generation 切片或任务所属的数据代次。
     * @param block 当前处理的结构块。
     * @param spec 文档入库配置或当前切片策略参数。
     * @param parserVersion 本次解析器使用的版本标记。
     */
    private void table(
            List<ChunkBO> out,
            UUID doc,
            long generation,
            ParsedBlockBO block,
            SplitterBO spec,
            String parserVersion) {
        // rows：数据库结果或当前表格数据行集合。
        var rows = block.tableRows();
        // start：当前片段或表格分组的起始位置。
        int start = 0;
        do {
            // group：当前切片分组内的结构块或表格数据行。
            var group = new ArrayList<List<String>>();
            while (start + group.size() < rows.size() && group.size() < spec.tableRows()) {
                // candidate：尝试加入下一数据行后的表格分组，用于检查字符预算。
                var candidate = new ArrayList<>(group);
                candidate.add(rows.get(start + group.size()));
                // count：当前累计数量或本次流读取的有效字节数。
                int count = chars(MarkdownDocumentParser.table(block.tableHeader(), candidate));
                if (!group.isEmpty()
                        && count
                                > Math.min(
                                        limits.hardChunkChars(),
                                        spec.maxChars() + spec.toleranceChars())) break;
                if (count > limits.hardChunkChars()) throw tooLarge();
                group = candidate;
            }
            // content：用于展示或维护的正文内容。
            String content = MarkdownDocumentParser.table(block.tableHeader(), group);
            // semantic：为表格构造的嵌入语义文本。
            var semantic = new StringBuilder();
            // row：当前数据库记录或表格数据行。
            for (var row : group) {
                if (!semantic.isEmpty()) semantic.append('\n');
                // i：当前结构块、文本行或表格列的遍历位置。
                for (int i = 0; i < block.tableHeader().size(); i++) {
                    if (i > 0) semantic.append("；");
                    semantic.append(block.tableHeader().get(i))
                            .append("：")
                            .append(i < row.size() ? row.get(i) : "");
                }
            }
            if (semantic.isEmpty()) semantic.append(String.join("；", block.tableHeader()));
            // location：当前结构的来源位置或远程重定向目标。
            var location = new LinkedHashMap<String, Object>();
            location.put("rowStart", start + 1);
            location.put("rowEnd", start + group.size());
            location.put("headerRepeated", true);
            // numbers：向量数值或来源行号的原始列表。
            if (block.source().get("dataRowNumbers") instanceof List<?> numbers
                    && !group.isEmpty()) {
                // selected：当前选定的切片、表格行或业务记录集合。
                var selected = numbers.subList(start, start + group.size());
                location.put("rowNumbers", selected);
                location.put("rowStart", selected.get(0));
                location.put("rowEnd", selected.get(selected.size() - 1));
            }
            // field：当前需要读取或过滤的字段名。
            for (String field :
                    List.of(
                            "sheetName",
                            "tableName",
                            "headerRow",
                            "rowNumberUnit",
                            "formulaPolicy"))
                if (block.source().containsKey(field))
                    location.put(field, block.source().get(field));
            emit(
                    out,
                    doc,
                    generation,
                    content,
                    semantic.toString(),
                    List.of(block),
                    location,
                    parserVersion);
            start += group.size();
        } while (start < rows.size());
    }

    /**
     * 校验正文与嵌入预算，补齐章节及来源元数据并生成稳定切片标识。
     *
     * @param out 当前结果集合或文件输出流。
     * @param doc 本次操作涉及的文档唯一标识。
     * @param generation 切片或任务所属的数据代次。
     * @param content 用于展示或维护的正文内容。
     * @param embedding 发送给嵌入模型的文本，包含必要的章节或表格语义。
     * @param blocks 按源文档顺序排列的结构块。
     * @param extra 切片策略附加的来源定位字段。
     * @param parserVersion 本次解析器使用的版本标记。
     */
    private void emit(
            List<ChunkBO> out,
            UUID doc,
            long generation,
            String content,
            String embedding,
            List<ParsedBlockBO> blocks,
            Map<String, Object> extra,
            String parserVersion) {
        if (content.isBlank()) return;
        if (chars(content) > limits.hardChunkChars()) throw tooLarge();
        if (out.size() >= limits.maxChunks())
            throw new KnowledgeBaseException(
                    422, "KB_TOO_MANY_CHUNKS", "Document exceeds configured chunk count");
        // path：当前结构块所属的章节路径。
        List<String> path =
                extra.containsKey("wholeDocument") ? List.of() : blocks.get(0).chapterPath();
        // prefix：嵌入文本前附加的章节路径或来源提示。
        String prefix = path.isEmpty() ? "" : "章节：" + String.join(" > ", path) + "\n";
        if (!embedding.startsWith(prefix)) embedding = prefix + embedding;
        if (chars(embedding) > limits.maxEmbeddingChars()) throw tooLarge();
        // metadata：上传业务元数据或切片来源定位字段。
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("chapterPath", path);
        metadata.put("parserVersion", parserVersion);
        metadata.put(
                "blockType",
                extra.containsKey("wholeDocument") ? "DOCUMENT" : blocks.get(0).blockType().name());
        metadata.put(
                "sourceBlocks",
                blocks.stream()
                        .map(
                                b -> {
                                    // b：当前需要保留结构及来源信息的解析块。
                                    // source：当前结构块的来源字段或已校验临时源文件。
                                    var source = new LinkedHashMap<>(b.source());
                                    source.remove("dataRowNumbers");
                                    source.put("blockIndex", b.index());
                                    return source;
                                })
                        .toList());
        metadata.putAll(extra);
        // hash：源内容或切片正文的稳定摘要。
        String hash = EmbeddingCatalog.sha256(content);
        // id：当前业务对象的唯一标识。
        UUID id =
                UUID.nameUUIDFromBytes(
                        (doc + ":" + generation + ":" + out.size() + ":" + hash)
                                .getBytes(StandardCharsets.UTF_8));
        out.add(
                new ChunkBO(
                        id,
                        out.size(),
                        content,
                        embedding,
                        hash,
                        chars(content),
                        Map.copyOf(metadata)));
    }

    /**
     * 按 Unicode 码点统计字符数，避免将代理对计算为两个字符。
     *
     * @param text 当前待处理或生成的文本内容。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private static int chars(String text) {
        return text.codePointCount(0, text.length());
    }

    /**
     * 创建文档不存在可索引内容的异常。
     *
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private static KnowledgeBaseException empty() {
        return new KnowledgeBaseException(
                422, "KB_EMPTY_CONTENT", "Document has no indexable content");
    }

    /**
     * 创建结构或文件超过配置容量的异常。
     *
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private static KnowledgeBaseException tooLarge() {
        return new KnowledgeBaseException(
                422,
                "KB_STRUCTURE_TOO_LARGE",
                "Structure exceeds hard content/embedding budget; adjust config or source");
    }

    /**
     * 创建输入或协议校验失败的业务异常。
     *
     * @param message 对外展示的业务错误或状态说明。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private static KnowledgeBaseException invalid(String message) {
        return new KnowledgeBaseException(400, "KB_INVALID_INPUT", message);
    }
}
