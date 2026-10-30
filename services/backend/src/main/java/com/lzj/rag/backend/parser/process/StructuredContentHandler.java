package com.lzj.rag.backend.parser.process;

import com.lzj.rag.backend.knowledgebase.exception.KnowledgeBaseException;
import com.lzj.rag.backend.parser.enums.BlockType;
import com.lzj.rag.backend.parser.service.bo.ParsedBlockBO;
import com.lzj.rag.backend.parser.service.bo.ParsedDocumentBO;
import com.lzj.rag.backend.parser.strategy.MarkdownDocumentParser;
import com.lzj.rag.backend.parser.support.TableSupport;

import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

import java.time.Instant;
import java.util.*;

/** 消费 Tika SAX 事件，按标题、段落和表格生成有界的结构化内容。 */
public final class StructuredContentHandler extends DefaultHandler {

    /** 当前文本或切片允许的字符数上限。 */
    private final int maxChars;

    /** 允许解析的 PDF 页数上限。 */
    private final int maxPages;

    /** 当前阶段使用的绝对截止时间。 */
    private final Instant deadline;

    /** 按源文档顺序排列的结构块。 */
    private final List<ParsedBlockBO> blocks = new ArrayList<>();

    /** 当前标题层级到标题文本的映射或标题文本集合。 */
    private final List<String> headings = new ArrayList<>();

    /** 逐步构造的文本缓冲区。 */
    private final StringBuilder all = new StringBuilder();

    /** 逐步构造的文本缓冲区。 */
    private final StringBuilder pending = new StringBuilder();

    /** 按 Unicode 码点统计的字符数。 */
    private int chars;

    /** 当前源页面编号或分页页码。 */
    private int page;

    /** 当前幻灯片编号。 */
    private int slide;

    /** 当前 SAX 解析所处的表格嵌套深度。 */
    private int tableDepth;

    /** 当前 SAX 元素的规范化标签名。 */
    private String tag;

    /** 数据库结果或当前表格数据行集合。 */
    private List<List<String>> rows;

    /** 当前数据库记录或表格数据行。 */
    private List<String> row;

    /** 逐步构造的文本缓冲区。 */
    private StringBuilder cell;

    /**
     * 初始化当前组件，保存业务依赖及本次使用的配置。
     *
     * @param maxChars 当前文本或切片允许的字符数上限。
     * @param maxPages 允许解析的 PDF 页数上限。
     * @param deadline 本阶段或本次请求必须完成的截止时间。
     */
    StructuredContentHandler(int maxChars, int maxPages, Instant deadline) {
        this.maxChars = maxChars;
        this.maxPages = maxPages;
        this.deadline = deadline;
    }

    /** 检查解析截止时间及线程中断，超过预算时及时终止 SAX 处理。 */
    private void check() {
        if (Thread.currentThread().isInterrupted() || Instant.now().isAfter(deadline))
            throw new KnowledgeBaseException(504, "KB_PARSE_TIMEOUT", "Local parse budget expired");
    }

    /**
     * 进入结构标签时更新标题、段落和表格解析状态。
     *
     * @param uri 当前 XML 命名空间或外部请求 URI。
     * @param local 当前元素本地名、局部解析器或阶段截止时间。
     * @param name 当前业务资源或策略的公开名称。
     * @param attributes 当前 SAX 元素携带的属性集合。
     */
    @Override
    public void startElement(String uri, String local, String name, Attributes attributes) {
        check();
        // t：优先使用本地名后得到的当前 XML 标签名。
        String t = local.isEmpty() ? name : local;
        // cls：解析器子进程使用的 Java 类路径。
        String cls = attributes.getValue("class");
        if (t.equals("div") && "page".equals(cls)) {
            flush();
            if (++page > maxPages) throw limit();
        }
        if (t.equals("div") && "slide-content".equals(cls)) {
            flush();
            if (++slide > maxPages) throw limit();
        }
        if (t.equals("table")) {
            flush();
            if (++tableDepth > 1)
                throw new KnowledgeBaseException(
                        422,
                        "KB_TABLE_STRUCTURE_UNSUPPORTED",
                        "Nested tables require an explicit layout parser");
            rows = new ArrayList<>();
        }
        if (tableDepth > 0) {
            if (t.equals("tr")) row = new ArrayList<>();
            if (Set.of("td", "th").contains(t)) {
                cell = new StringBuilder();
                // key：对象存储键、配置键或业务幂等键。
                for (String key : List.of("colspan", "rowspan")) {
                    // span：HTML 单元格声明的跨行或跨列数量。
                    String span = attributes.getValue(key);
                    if (span != null && !span.equals("1"))
                        throw new KnowledgeBaseException(
                                422,
                                "KB_TABLE_STRUCTURE_UNSUPPORTED",
                                "Spanning Office tables need layout normalization; current fidelity route accepts PDF only");
                }
            }
            return;
        }
        if (t.matches("h[1-6]") || Set.of("p", "li", "pre").contains(t)) {
            flush();
            tag = t;
        }
    }

    /**
     * 将当前文本片段追加到对应结构缓冲区，并校验字符预算。
     *
     * @param ch 当前处理的 Unicode 码点或 SAX 字符片段。
     * @param start 当前片段或表格分组的起始位置。
     * @param length 本次处理的字符或缓冲片段长度。
     */
    @Override
    public void characters(char[] ch, int start, int length) {
        check();
        if ((chars += length) > maxChars) throw limit();
        if (cell != null) cell.append(ch, start, length);
        else if (tableDepth == 0 && tag != null) pending.append(ch, start, length);
    }

    /**
     * 关闭结构标签并在边界处输出标题、段落或表格块。
     *
     * @param uri 当前 XML 命名空间或外部请求 URI。
     * @param local 当前元素本地名、局部解析器或阶段截止时间。
     * @param name 当前业务资源或策略的公开名称。
     */
    @Override
    public void endElement(String uri, String local, String name) {
        // t：优先使用本地名后得到的当前 XML 标签名。
        String t = local.isEmpty() ? name : local;
        if (tableDepth > 0) {
            if (Set.of("td", "th").contains(t) && cell != null) {
                if (row != null) row.add(cell.toString().strip());
                cell = null;
            }
            if (t.equals("tr") && row != null) {
                rows.add(List.copyOf(row));
                row = null;
            }
            if (t.equals("table")) {
                tableDepth--;
                if (rows != null && !rows.isEmpty()) {
                    // header：当前表格的列标题集合。
                    var header = TableSupport.header(rows.get(0));
                    // data：当前表格数据或待处理字节内容。
                    var data = rows.subList(1, rows.size());
                    // width：图片宽度，单位为像素。
                    int width = header.size();
                    // r：当前表格数据行，用于校验列数是否规则。
                    for (var r : data)
                        if (r.size() != width)
                            throw new KnowledgeBaseException(
                                    422,
                                    "KB_TABLE_STRUCTURE_UNSUPPORTED",
                                    "Irregular Office table requires fidelity parsing");
                    // metadata：上传业务元数据或切片来源定位字段。
                    var metadata = source();
                    metadata.put("headerPolicy", "FIRST_ROW");
                    add(
                            BlockType.TABLE,
                            MarkdownDocumentParser.table(header, data),
                            metadata,
                            header,
                            data);
                }
                rows = null;
            }
            return;
        }
        if (t.equals(tag)) flush();
    }

    /**
     * 生成当前结构块的来源坐标信息。
     *
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private Map<String, Object> source() {
        // result：当前步骤产生的处理结果。
        var result = new LinkedHashMap<String, Object>();
        if (page > 0) result.put("pageNumber", page);
        if (slide > 0) result.put("slideNumber", slide);
        return result;
    }

    /** 将待输出正文转换为结构块并重置文本缓冲区。 */
    private void flush() {
        if (tag == null) return;
        // value：当前待校验、转换或写入的值。
        String value = pending.toString().strip();
        pending.setLength(0);
        // current：分页页码或当前处理的中间对象。
        String current = tag;
        tag = null;
        if (value.isBlank()) return;
        // type：当前内容或数据库字段的类型标识。
        BlockType type = BlockType.PARAGRAPH;
        if (current.matches("h[1-6]")) {
            // level：标题层级。
            int level = current.charAt(1) - '0';
            while (headings.size() >= level) headings.remove(headings.size() - 1);
            headings.add(value);
            type = BlockType.HEADING;
        } else if (current.equals("li")) type = BlockType.LIST;
        else if (current.equals("pre")) type = BlockType.CODE;
        add(type, value, source(), List.of(), List.of());
    }

    /**
     * 追加一个结构块并维护块序号、标题路径及字符统计。
     *
     * @param type 当前内容或数据库字段的类型标识。
     * @param content 用于展示或维护的正文内容。
     * @param metadata 上传业务元数据或切片来源定位字段。
     * @param header 当前表格的列标题集合。
     * @param data 当前表格数据或待处理字节内容。
     */
    private void add(
            BlockType type,
            String content,
            Map<String, Object> metadata,
            List<String> header,
            List<List<String>> data) {
        if (!all.isEmpty()) all.append("\n\n");
        all.append(content);
        if (all.length() > maxChars) throw limit();
        blocks.add(
                new ParsedBlockBO(
                        blocks.size(),
                        type,
                        content,
                        List.copyOf(headings),
                        Map.copyOf(metadata),
                        List.copyOf(header),
                        List.copyOf(data)));
    }

    /**
     * 结束剩余缓冲并返回完整解析结果。
     *
     * @param version 当前记录版本或本次请求固定的版本标记。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    ParsedDocumentBO result(String version) {
        flush();
        return new ParsedDocumentBO(all.toString(), List.copyOf(blocks), version);
    }

    /**
     * 创建解析内容超过配置容量的异常。
     *
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private KnowledgeBaseException limit() {
        return new KnowledgeBaseException(
                422, "KB_PARSE_LIMIT", "Document exceeds page/text parsing limits");
    }
}
