package com.lzj.rag.backend.parser.process;

import com.lzj.rag.backend.knowledgebase.exception.KnowledgeBaseException;
import com.lzj.rag.backend.parser.config.FormatProperties;
import com.lzj.rag.backend.parser.enums.BlockType;
import com.lzj.rag.backend.parser.service.bo.ParsedBlockBO;
import com.lzj.rag.backend.parser.service.bo.ParsedDocumentBO;
import com.lzj.rag.backend.parser.service.bo.ParserInputBO;
import com.lzj.rag.backend.parser.strategy.MarkdownDocumentParser;
import com.lzj.rag.backend.parser.support.SupportedFiles;
import com.lzj.rag.backend.parser.support.TableSupport;

import org.apache.commons.csv.*;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.*;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.microsoft.OfficeParser;
import org.apache.tika.parser.microsoft.ooxml.OOXMLParser;
import org.apache.tika.parser.pdf.*;
import org.xml.sax.ContentHandler;

import java.io.*;
import java.util.*;

/** 在受限解析子进程中提取正文和表格，保留页码、工作表及行列来源。 */
public final class LocalFormatExtractor {

    /**
     * 按文件类型选择本地提取路径，限制嵌套内容并返回结构化文档。
     *
     * @param input 解析源文件及其字符、时间约束。
     * @param config 本地解析、MinerU 和视觉模型的开关、容量与时间限制。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    static ParsedDocumentBO parse(ParserInputBO input, FormatProperties config) throws Exception {
        if (input.mime().equals("application/x-kb-pdf-inspection")) {
            // pdf：当前校验的 PDF 文档。
            try (var pdf = org.apache.pdfbox.Loader.loadPDF(input.path().toFile())) {
                if (pdf.getNumberOfPages() > config.maxPages()) throw limit();
            }
            return new ParsedDocumentBO("", List.of(), "pdf-inspection-v1");
        }
        if (input.mime().equals("text/csv")
                || SupportedFiles.extension(input.filename()).equals("csv"))
            return csv(input, config);
        if (SupportedFiles.EXCEL.contains(input.mime())) return excel(input, config);
        // context：当前入库尝试的固定输入及执行租约上下文。
        var context = new ParseContext();
        // pdfConfig：PDF 解析器的资源与提取参数。
        var pdfConfig = new PDFParserConfig();
        pdfConfig.setOcrStrategy(PDFParserConfig.OCR_STRATEGY.NO_OCR);
        pdfConfig.setExtractInlineImages(false);
        pdfConfig.setExtractAcroFormContent(false);
        context.set(PDFParserConfig.class, pdfConfig);
        context.set(
                EmbeddedDocumentExtractor.class,
                new EmbeddedDocumentExtractor() {

                    /**
                     * 判断是否允许解析嵌入对象，避免对子文档递归展开。
                     *
                     * @param m 嵌入文档的 Tika 元数据；当前策略禁止递归提取。
                     * @return 当前业务处理结果；失败时通过异常报告。
                     */
                    public boolean shouldParseEmbedded(Metadata m) {
                        return false;
                    }

                    /**
                     * 处理解析器请求的嵌入内容回调。
                     *
                     * @param in 当前文件或响应的输入流，使用结束后关闭。
                     * @param handler 接收格式解析事件并生成结构块的处理器。
                     * @param m 嵌入文档的 Tika 元数据；当前策略禁止递归提取。
                     * @param html 当前内容是否使用 HTML 表示，或待解析的表格 HTML。
                     */
                    public void parseEmbedded(
                            InputStream in, ContentHandler handler, Metadata m, boolean html) {}
                });
        // handler：接收格式解析事件并生成结构块的处理器。
        var handler =
                new StructuredContentHandler(
                        input.maxParsedChars(), config.maxPages(), input.deadline());
        // parser：当前选择的解析策略或解析器标识。
        org.apache.tika.parser.Parser parser =
                input.mime().equals("application/pdf")
                        ? new PDFParser()
                        : input.mime().contains("openxmlformats")
                                ? new OOXMLParser()
                                : new OfficeParser();
        // stream：当前需要关闭的文件或解析资源流。
        try (var stream = java.nio.file.Files.newInputStream(input.path())) {
            parser.parse(stream, handler, new Metadata(), context);
        }
        // parsed：源文件解析得到的统一结构化文档。
        var parsed = handler.result("tika-3.3.3-fast-no-ocr-v1");
        // b：当前需要保留结构及来源信息的解析块。
        if (parsed.blocks().stream()
                .noneMatch(b -> b.blockType() != BlockType.HEADING && !b.content().isBlank()))
            throw new KnowledgeBaseException(
                    422,
                    input.mime().equals("application/pdf") ? "KB_OCR_REQUIRED" : "KB_EMPTY_CONTENT",
                    "No extractable text; scanned PDF requires configured fidelity/OCR");
        return parsed;
    }

    /**
     * 逐工作表读取行列，处理公式和表头并保留原始坐标。
     *
     * @param input 解析源文件及其字符、时间约束。
     * @param config 本地解析、MinerU 和视觉模型的开关、容量与时间限制。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private static ParsedDocumentBO excel(ParserInputBO input, FormatProperties config)
            throws Exception {
        ZipSecureFile.setMinInflateRatio(0.01);
        ZipSecureFile.setMaxEntrySize(16777216);
        ZipSecureFile.setMaxTextSize(input.maxParsedChars());
        // blocks：按源文档顺序排列的结构块。
        var blocks = new ArrayList<ParsedBlockBO>();
        // text：当前待处理或生成的文本内容。
        var text = new StringBuilder();
        // book：当前读取的 Excel 工作簿。
        try (var book = WorkbookFactory.create(input.path().toFile(), null, true)) {
            if (book.getNumberOfSheets() > config.maxSheets()) throw limit();
            // format：当前单元格或文件使用的格式信息。
            var format = new DataFormatter(Locale.ROOT);
            // evaluator：读取 Excel 公式结果时使用的公式求值器。
            var evaluator = book.getCreationHelper().createFormulaEvaluator();
            evaluator.setIgnoreMissingWorkbooks(false);
            // totalRows：已处理的表格数据行总数。
            int totalRows = 0;
            // totalChars：已累计输出的解析字符总数。
            int totalChars = 0;
            // sheet：当前读取的 Excel 工作表。
            for (var sheet : book) {
                if (sheet.getLastRowNum() + 1 > config.maxRows()) throw limit();
                // header：当前表格的列标题集合。
                List<String> header = null;
                // rows：数据库结果或当前表格数据行集合。
                var rows = new ArrayList<List<String>>();
                // numbers：向量数值或来源行号的原始列表。
                var numbers = new ArrayList<Integer>();
                // headerRow：源工作表中表头所在的原始行号。
                int headerRow = 0;
                // row：当前数据库记录或表格数据行。
                for (var row : sheet) {
                    if (++totalRows > config.maxRows()
                            || row.getLastCellNum() > config.maxColumns()) throw limit();
                    // width：图片宽度，单位为像素。
                    int width = header == null ? Math.max(0, row.getLastCellNum()) : header.size();
                    if (header != null && row.getLastCellNum() > width)
                        throw new KnowledgeBaseException(
                                422, "KB_TABLE_ROW_WIDTH", "Data extends beyond header columns");
                    // values：本次处理的参数值或指标快照集合。
                    var values = new ArrayList<String>();
                    // i：当前遍历位置，按原始输入顺序递增。
                    for (int i = 0; i < width; i++) {
                        // cell：当前表格单元格或其文本缓冲。
                        Cell cell = row.getCell(i, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                        // value：当前待校验、转换或写入的值。
                        String value = "";
                        if (cell != null)
                            try {
                                if (cell.getCellType() == CellType.FORMULA
                                        && evaluator.evaluate(cell).getCellType() == CellType.ERROR)
                                    throw new IllegalArgumentException(
                                            "Formula evaluated to error");
                                value = format.formatCellValue(cell, evaluator);
                            } catch (RuntimeException error) {
                                // error：本次处理捕获的异常，用于归类失败原因。
                                throw new KnowledgeBaseException(
                                        422,
                                        "KB_FORMULA_EVALUATION_FAILED",
                                        "Formula cannot be evaluated; resolve errors or replace it with a reviewed value");
                            }
                        totalChars += value.length();
                        if (totalChars > input.maxParsedChars()) throw limit();
                        values.add(value);
                    }
                    if (values.stream().allMatch(String::isBlank)) continue;
                    if (header == null) {
                        header = TableSupport.header(values);
                        headerRow = row.getRowNum() + 1;
                    } else {
                        rows.add(List.copyOf(values));
                        numbers.add(row.getRowNum() + 1);
                    }
                }
                if (header != null)
                    addTable(
                            blocks,
                            text,
                            header,
                            rows,
                            Map.of(
                                    "sheetName",
                                    sheet.getSheetName(),
                                    "headerRow",
                                    headerRow,
                                    "dataRowNumbers",
                                    numbers,
                                    "formulaPolicy",
                                    "EVALUATE"),
                            input.maxParsedChars());
            }
        }
        return new ParsedDocumentBO(text.toString(), List.copyOf(blocks), "poi-5.5.1-evaluate-v1");
    }

    /**
     * 按 CSV 格式规则读取记录，限制行列数并保留表头及行号。
     *
     * @param input 解析源文件及其字符、时间约束。
     * @param config 本地解析、MinerU 和视觉模型的开关、容量与时间限制。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private static ParsedDocumentBO csv(ParserInputBO input, FormatProperties config)
            throws IOException {
        // blocks：按源文档顺序排列的结构块。
        var blocks = new ArrayList<ParsedBlockBO>();
        // text：当前待处理或生成的文本内容。
        var text = new StringBuilder();
        // reader：当前受限文本或图片读取器。
        // raw：尚未校验或规范化的原始输入。
        // 严格按 UTF-8 和 RFC4180 读取，允许引号内换行；等号仅作为普通文本处理。
        try (var raw = SupportedFiles.utf8(input.path());
                var reader = new PushbackReader(raw, 1)) {
            // first：当前处理范围的起始位置或首个有效内容。
            int first = reader.read();
            if (first != -1 && first != 0xfeff) reader.unread(first);
            // parser：当前选择的解析策略或解析器标识。
            try (var parser =
                    CSVFormat.RFC4180.builder().setIgnoreEmptyLines(false).get().parse(reader)) {
                // header：当前表格的列标题集合。
                List<String> header = null;
                // rows：数据库结果或当前表格数据行集合。
                var rows = new ArrayList<List<String>>();
                // numbers：向量数值或来源行号的原始列表。
                var numbers = new ArrayList<Integer>();
                // chars：按 Unicode 码点统计的字符数。
                int chars = 0;
                // record：当前输入的 CSV 记录或向量存储记录。
                for (var record : parser) {
                    if (record.getRecordNumber() > config.maxRows()
                            || record.size() > config.maxColumns()) throw limit();
                    // values：本次处理的参数值或指标快照集合。
                    var values = new ArrayList<String>();
                    // v：当前表格单元格文本或字段值。
                    for (String v : record) {
                        chars += v.length();
                        if (chars > input.maxParsedChars()) throw limit();
                        values.add(v);
                    }
                    if (header == null) {
                        header = TableSupport.header(values);
                        continue;
                    }
                    if (values.size() != header.size())
                        throw new KnowledgeBaseException(
                                422,
                                "KB_CSV_ROW_WIDTH",
                                "CSV row width must match its header; empty fields must retain delimiters");
                    rows.add(List.copyOf(values));
                    numbers.add(Math.toIntExact(record.getRecordNumber()));
                }
                if (header != null)
                    addTable(
                            blocks,
                            text,
                            header,
                            rows,
                            Map.of(
                                    "tableName",
                                    "CSV",
                                    "headerRow",
                                    1,
                                    "dataRowNumbers",
                                    numbers,
                                    "formulaPolicy",
                                    "LITERAL_TEXT",
                                    "rowNumberUnit",
                                    "CSV_RECORD"),
                            input.maxParsedChars());
            }
        }
        return new ParsedDocumentBO(
                text.toString(), List.copyOf(blocks), "commons-csv-1.14.1-rfc4180-v1");
    }

    /**
     * 将表格与来源信息追加为结构块，并检查总字符预算。
     *
     * @param blocks 按源文档顺序排列的结构块。
     * @param text 当前待处理或生成的文本内容。
     * @param header 当前表格的列标题集合。
     * @param rows 数据库结果或当前表格数据行集合。
     * @param source 当前结构块的来源字段或已校验临时源文件。
     * @param max 当前校验允许的最大长度或数值。
     */
    private static void addTable(
            List<ParsedBlockBO> blocks,
            StringBuilder text,
            List<String> header,
            List<List<String>> rows,
            Map<String, Object> source,
            int max) {
        // content：用于展示或维护的正文内容。
        String content = MarkdownDocumentParser.table(header, rows);
        if (text.length() + content.length() > max) throw limit();
        if (!text.isEmpty()) text.append("\n\n");
        text.append(content);
        // name：当前业务资源或策略的公开名称。
        String name =
                String.valueOf(
                        source.getOrDefault(
                                "sheetName", source.getOrDefault("tableName", "Table")));
        blocks.add(
                new ParsedBlockBO(
                        blocks.size(),
                        BlockType.TABLE,
                        content,
                        List.of(name),
                        source,
                        List.copyOf(header),
                        List.copyOf(rows)));
    }

    /**
     * 创建解析内容超过配置容量的异常。
     *
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private static KnowledgeBaseException limit() {
        return new KnowledgeBaseException(
                422,
                "KB_PARSE_LIMIT",
                "Spreadsheet/table exceeds configured row/column/sheet/text limits");
    }
}
