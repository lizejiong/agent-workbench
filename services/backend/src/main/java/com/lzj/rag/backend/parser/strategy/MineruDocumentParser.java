package com.lzj.rag.backend.parser.strategy;

import com.fasterxml.jackson.databind.*;
import com.lzj.rag.backend.knowledgebase.exception.KnowledgeBaseException;
import com.lzj.rag.backend.model.client.AiEmbeddingClient;
import com.lzj.rag.backend.parser.config.FormatProperties;
import com.lzj.rag.backend.parser.enums.BlockType;
import com.lzj.rag.backend.parser.service.bo.ParsedBlockBO;
import com.lzj.rag.backend.parser.service.bo.ParsedDocumentBO;
import com.lzj.rag.backend.parser.service.bo.ParserInputBO;
import com.lzj.rag.backend.parser.support.TableSupport;

import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;

import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** 调用 MinerU 高保真解析服务，将结构化返回映射为统一文档块。 */
@Component
public class MineruDocumentParser implements DocumentParser {

    /** 本地解析、MinerU 和视觉模型的开关、容量与时间限制。 */
    private final FormatProperties config;

    /** 用于业务请求、持久化快照与元数据转换的 JSON 映射器。 */
    private final ObjectMapper json;

    /** 在受限子进程中解析本地文件的组件。 */
    private final LocalFormatParser local;

    /** 配置了连接预算的 JDK HTTP 客户端。 */
    private final HttpClient client;

    /** 限制当前业务并发执行数量的许可集合。 */
    private final Semaphore permits;

    /**
     * 初始化当前组件，保存业务依赖及本次使用的配置。
     *
     * @param config 本地解析、MinerU 和视觉模型的开关、容量与时间限制。
     * @param json 用于业务请求、持久化快照与元数据转换的 JSON 映射器。
     * @param local 在受限子进程中解析本地文件的组件。
     */
    public MineruDocumentParser(
            FormatProperties config, ObjectMapper json, LocalFormatParser local) {
        this.config = config;
        this.json = json;
        this.local = local;
        permits = new Semaphore(config.mineru().concurrency());
        client =
                HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .connectTimeout(config.mineru().connectTimeout())
                        .build();
        // c：MinerU 服务连接及资源限制配置。
        var c = config.mineru();
        if (c.enabled()) {
            // uri：当前 XML 命名空间或外部请求 URI。
            URI uri = URI.create(c.baseUrl());
            if (!Set.of("http", "https").contains(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null
                    || !"mineru-2.7.6-file-parse".equals(c.protocol())
                    || !Set.of("pipeline", "hybrid-auto-engine", "vlm-auto-engine")
                            .contains(c.backend())
                    || !c.language().matches("[a-z_]{2,20}")
                    || c.timeout().isNegative()
                    || c.timeout().isZero()
                    || c.timeout().compareTo(Duration.ofMinutes(5)) > 0)
                throw new IllegalArgumentException(
                        "Invalid MinerU endpoint/protocol/backend/language/time budget");
        }
    }

    /**
     * 返回解析器的稳定策略标识。
     *
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public String profile() {
        return "BINARY";
    }

    /**
     * 返回当前解析器支持的处理档位。
     *
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public String parseProfile() {
        return "fidelity";
    }

    /**
     * 返回当前解析策略支持的 MIME 类型集合。
     *
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public Set<String> mimeTypes() {
        return Set.of("application/pdf");
    }

    /**
     * 拒绝未经文件校验的纯文本输入；该解析策略要求使用受限文件输入。
     *
     * @param text 当前待处理或生成的文本内容。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public ParsedDocumentBO parse(String text) {
        throw unavailable();
    }

    /**
     * 按当前解析策略读取输入，在容量与时间限制内生成统一结构块。
     *
     * @param input 解析源文件及其字符、时间约束。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @Override
    public ParsedDocumentBO parse(ParserInputBO input) {
        if (!config.mineru().enabled()) throw unavailable();
        // 发送源字节前先检查页数与加密状态，超限时明确拒绝，不截断后继续解析。
        local.validatePdf(input);
        // acquired：是否已经取得本次操作所需并发许可。
        boolean acquired = false;
        // deadline：本阶段或本次请求必须完成的截止时间。
        Instant deadline = Instant.now().plus(config.mineru().timeout());
        if (input.deadline().isBefore(deadline)) deadline = input.deadline();
        try {
            // wait：本次等待允许使用的时间量。
            long wait = Duration.between(Instant.now(), deadline).toMillis();
            if (wait <= 0 || !(acquired = permits.tryAcquire(wait, TimeUnit.MILLISECONDS)))
                throw timeout();
            // schema：关键词索引的字段结构定义。
            var schema =
                    call(
                            "GET",
                            "/openapi.json",
                            HttpRequest.BodyPublishers.noBody(),
                            "application/json",
                            input.requestId(),
                            deadline);
            // operation：持久化上传操作、操作标识或审计动作名称。
            var operation =
                    schema.at(
                            "/paths/~1file_parse/post/requestBody/content/multipart~1form-data/schema");
            // properties：当前组件绑定的业务配置。
            JsonNode properties = operation.path("properties");
            if (operation.has("$ref"))
                properties =
                        schema.path("components")
                                .path("schemas")
                                .path(
                                        operation
                                                .path("$ref")
                                                .asText()
                                                .replace("#/components/schemas/", ""))
                                .path("properties");
            // field：当前需要读取或过滤的字段名。
            for (String field :
                    List.of(
                            "files",
                            "return_content_list",
                            "return_images",
                            "return_md",
                            "response_format_zip",
                            "backend")) if (!properties.has(field)) throw protocol();
            // boundary：多段上传的边界标识或当前内容分组边界。
            String boundary = "kb-" + UUID.randomUUID();
            // parts：按分隔符拆分的文本片段。
            var parts = new ArrayList<HttpRequest.BodyPublisher>();
            // fields：当前 JSON 对象或模型响应中的字段集合。
            var fields =
                    Map.of(
                            "backend",
                            config.mineru().backend(),
                            "parse_method",
                            "auto",
                            "lang_list",
                            config.mineru().language(),
                            "return_md",
                            "false",
                            "return_images",
                            "false",
                            "return_content_list",
                            "true",
                            "response_format_zip",
                            "false",
                            "formula_enable",
                            "true",
                            "table_enable",
                            "true");
            // entry：当前处理的键值项。
            for (var entry : fields.entrySet())
                parts.add(
                        HttpRequest.BodyPublishers.ofString(
                                "--"
                                        + boundary
                                        + "\r\nContent-Disposition: form-data; name=\""
                                        + entry.getKey()
                                        + "\"\r\n\r\n"
                                        + entry.getValue()
                                        + "\r\n"));
            parts.add(
                    HttpRequest.BodyPublishers.ofString(
                            "--"
                                    + boundary
                                    + "\r\nContent-Disposition: form-data; name=\"files\"; filename=\"document.pdf\"\r\nContent-Type: application/pdf\r\n\r\n"));
            parts.add(HttpRequest.BodyPublishers.ofFile(input.path()));
            parts.add(HttpRequest.BodyPublishers.ofString("\r\n--" + boundary + "--\r\n"));
            // response：当前 HTTP 响应或外部服务返回结果。
            var response =
                    call(
                            "POST",
                            "/file_parse",
                            HttpRequest.BodyPublishers.concat(
                                    parts.toArray(HttpRequest.BodyPublisher[]::new)),
                            "multipart/form-data; boundary=" + boundary,
                            input.requestId(),
                            deadline);
            if (!"2.7.6".equals(response.path("version").asText())
                    || !config.mineru().backend().equals(response.path("backend").asText())
                    || response.path("results").size() != 1) throw protocol();
            // list：当前加载的业务记录集合。
            var list = response.path("results").path("document").path("content_list");
            if (!list.isTextual()) throw protocol();
            list = json.readTree(list.textValue());
            if (!list.isArray()) throw protocol();
            return structured(list, input.maxParsedChars());
        } catch (KnowledgeBaseException e) {
            // e：本次捕获或匹配的异常，用于保留业务分类及失败原因。
            throw e;
        } catch (InterruptedException e) {
            // e：本次捕获或匹配的异常，用于保留业务分类及失败原因。
            Thread.currentThread().interrupt();
            throw timeout();
        } catch (java.io.IOException e) {
            // e：本次捕获或匹配的异常，用于保留业务分类及失败原因。
            throw protocol();
        } finally {
            if (acquired) permits.release();
        }
    }

    /**
     * 在请求截止时间内调用 MinerU，限制响应大小并区分协议与网络失败。
     *
     * @param method 外部 HTTP 请求方法。
     * @param path 当前需要读取、写入或清理的文件路径。
     * @param body 需要发送或当前接收的 HTTP 正文。
     * @param contentType 文件或 HTTP 正文内容类型。
     * @param requestId 请求跟踪标识或业务幂等键，按当前接口约定使用。
     * @param deadline 本阶段或本次请求必须完成的截止时间。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private JsonNode call(
            String method,
            String path,
            HttpRequest.BodyPublisher body,
            String contentType,
            String requestId,
            Instant deadline) {
        // timeout：当前阶段允许使用的局部时间预算。
        long timeout = Duration.between(Instant.now(), deadline).toMillis();
        if (timeout <= 0) throw timeout();
        // builder：当前组件或请求的参数构建器。
        var builder =
                HttpRequest.newBuilder(
                                URI.create(config.mineru().baseUrl().replaceAll("/+$", "") + path))
                        .timeout(Duration.ofMillis(timeout))
                        .header("Content-Type", contentType)
                        .header("X-Request-Id", requestId)
                        .method(method, body);
        if (!config.mineru().token().isBlank())
            builder.header("Authorization", "Bearer " + config.mineru().token());
        // info：HTTP 响应状态和头信息，用于选择受字节上限约束的正文订阅器。
        // future：本次异步操作对应的可等待、可取消结果。
        var future =
                client.sendAsync(
                        builder.build(),
                        info -> new AiEmbeddingClient.LimitedBody(config.maxResultBytes()));
        try {
            // response：当前 HTTP 响应或外部服务返回结果。
            var response = future.get(timeout, TimeUnit.MILLISECONDS);
            // status：本次外部 HTTP 响应或错误映射使用的状态码。
            int status = response.statusCode();
            if (status == 408 || status == 504) throw timeout();
            if (status == 429 || status >= 500)
                throw new KnowledgeBaseException(
                        503,
                        "KB_PARSE_SERVICE_UNAVAILABLE",
                        "MinerU temporarily unavailable; bounded job retries apply");
            if (status != 200) throw protocol();
            return json.readTree(response.body());
        } catch (TimeoutException e) {
            // e：本次捕获或匹配的异常，用于保留业务分类及失败原因。
            future.cancel(true);
            throw timeout();
        } catch (InterruptedException e) {
            // e：本次捕获或匹配的异常，用于保留业务分类及失败原因。
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw timeout();
        } catch (ExecutionException e) {
            // e：本次捕获或匹配的异常，用于保留业务分类及失败原因。
            if (e.getCause() instanceof java.net.http.HttpTimeoutException) throw timeout();
            if (e.getCause() instanceof IllegalStateException) throw protocol();
            throw new KnowledgeBaseException(
                    503, "KB_PARSE_SERVICE_UNAVAILABLE", "MinerU connection failed");
        } catch (java.io.IOException e) {
            // e：本次捕获或匹配的异常，用于保留业务分类及失败原因。
            throw protocol();
        }
    }

    /**
     * 将 MinerU 结构化节点转换为统一内容块，保留页面和区域坐标。
     *
     * @param entries 当前需要遍历的 JSON 或配置条目集合。
     * @param max 当前校验允许的最大长度或数值。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private ParsedDocumentBO structured(JsonNode entries, int max) {
        // blocks：按源文档顺序排列的结构块。
        var blocks = new ArrayList<ParsedBlockBO>();
        // text：当前待处理或生成的文本内容。
        var text = new StringBuilder();
        // headings：当前标题层级到标题文本的映射或标题文本集合。
        var headings = new ArrayList<String>();
        // item：当前处理的集合元素。
        for (var item : entries) {
            if (!item.path("page_idx").isIntegralNumber()
                    || !item.path("page_idx").canConvertToInt()
                    || item.path("page_idx").asInt() < 0
                    || item.path("page_idx").asInt() >= config.maxPages()) throw protocol();
            // source：当前结构块的来源字段或已校验临时源文件。
            var source = new LinkedHashMap<String, Object>();
            source.put("pageNumber", item.path("page_idx").asInt() + 1);
            source.put("provenance", "MINERU_2_7_6");
            if (item.path("bbox").isArray()) {
                // bbox：结构块在页面中的边界坐标。
                var bbox = new ArrayList<Double>();
                if (item.path("bbox").size() != 4) throw protocol();
                // n：页面边界框中待校验的坐标数值节点。
                for (var n : item.path("bbox")) {
                    if (!n.isNumber() || !Double.isFinite(n.doubleValue()) || n.doubleValue() < 0)
                        throw protocol();
                    bbox.add(n.doubleValue());
                }
                if (bbox.get(0) > bbox.get(2) || bbox.get(1) > bbox.get(3)) throw protocol();
                source.put("bbox", bbox);
            }
            // type：当前内容或数据库字段的类型标识。
            String type = item.path("type").asText();
            // content：用于展示或维护的正文内容。
            String content;
            // kind：当前结构块的类别或业务操作分类。
            BlockType kind = BlockType.PARAGRAPH;
            // header：当前表格的列标题集合。
            List<String> header = List.of();
            // rows：数据库结果或当前表格数据行集合。
            List<List<String>> rows = List.of();
            switch (type) {
                case "text" -> {
                    if (!item.path("text").isTextual()
                            || (item.hasNonNull("text_level")
                                    && (!item.path("text_level").isIntegralNumber()
                                            || !item.path("text_level").canConvertToInt())))
                        throw protocol();
                    content = item.path("text").asText("");
                    // level：标题层级。
                    int level = item.path("text_level").asInt(0);
                    if (level < 0 || level > 6) throw protocol();
                    if (level > 0) {
                        while (headings.size() >= level) headings.remove(headings.size() - 1);
                        headings.add(content);
                        kind = BlockType.HEADING;
                    }
                }
                case "equation" -> {
                    if (!item.path("text").isTextual()) throw protocol();
                    content = item.path("text").asText("");
                    kind = BlockType.CODE;
                }
                case "table" -> {
                    if (!item.path("table_body").isTextual()) throw protocol();
                    // grid：展开合并单元格后的表格网格。
                    var grid = htmlTable(item.path("table_body").asText(""), max);
                    if (grid.isEmpty()) throw protocol();
                    header = TableSupport.header(grid.get(0));
                    rows = List.copyOf(grid.subList(1, grid.size()));
                    content = MarkdownDocumentParser.table(header, rows);
                    kind = BlockType.TABLE;
                    source.put("headerPolicy", "FIRST_ROW");
                }
                case "image" -> {
                    // captions：表格或图片附带的标题说明集合。
                    var captions = new ArrayList<String>();
                    // key：对象存储键、配置键或业务幂等键。
                    for (String key : List.of("image_caption", "image_footnote"))
                        if (item.path(key).isArray())
                            // value：当前待校验、转换或写入的值。
                            for (var value : item.path(key)) captions.add(value.asText());
                    content = String.join("\n", captions);
                    source.put("provenance", "MINERU_IMAGE_CAPTION");
                }
                default ->
                        throw new KnowledgeBaseException(
                                502,
                                "KB_MINERU_CONTENT_UNSUPPORTED",
                                "MinerU returned an unsupported content block; no silent downgrade");
            }
            if (content.isBlank()) continue;
            if (text.length() + content.length() > max)
                throw new KnowledgeBaseException(
                        422, "KB_PARSE_LIMIT", "Layout text exceeds budget");
            if (!text.isEmpty()) text.append("\n\n");
            text.append(content);
            if (blocks.size() >= config.maxRows()) throw protocol();
            blocks.add(
                    new ParsedBlockBO(
                            blocks.size(),
                            kind,
                            content,
                            List.copyOf(headings),
                            Map.copyOf(source),
                            header,
                            rows));
        }
        // b：当前需要保留结构及来源信息的解析块。
        if (blocks.stream().noneMatch(b -> b.blockType() != BlockType.HEADING))
            throw new KnowledgeBaseException(
                    422, "KB_EMPTY_CONTENT", "MinerU returned no indexable body");
        return new ParsedDocumentBO(
                text.toString(), List.copyOf(blocks), "mineru-2.7.6-content-list-v1");
    }

    /**
     * 解析 HTML 表格并展开行列合并，限制输出行列规模。
     *
     * @param html 当前内容是否使用 HTML 表示，或待解析的表格 HTML。
     * @param max 当前校验允许的最大长度或数值。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private List<List<String>> htmlTable(String html, int max) {
        if (html.length() > max) throw protocol();
        // tables：当前节点包含的表格集合。
        var tables = Jsoup.parseBodyFragment(html).select("table");
        if (tables.size() != 1) throw protocol();
        // out：当前结果集合或文件输出流。
        var out = new ArrayList<List<String>>();
        // chars：按 Unicode 码点统计的字符数。
        int chars = 0;
        // r：当前 HTML 数据行在展开网格中的位置。
        int r = 0;
        // tr：当前 HTML 表格行节点。
        for (var tr : tables.first().select("tr")) {
            if (r >= config.maxRows()) throw protocol();
            while (out.size() <= r) out.add(new ArrayList<>());
            // col：当前表格列位置。
            int col = 0;
            // cell：当前表格单元格或其文本缓冲。
            for (var cell : tr.children()) {
                if (!Set.of("td", "th").contains(cell.tagName())) continue;
                // rs：当前 HTML 单元格跨行数量。
                int rs = span(cell.attr("rowspan"));
                // cs：当前 HTML 单元格跨列数量。
                int cs = span(cell.attr("colspan"));
                // value：当前待校验、转换或写入的值。
                String value = cell.text();
                while (col < out.get(r).size() && out.get(r).get(col) != null) col++;
                if (r + rs > config.maxRows() || col + cs > config.maxColumns()) throw protocol();
                // a：展开跨行单元格时当前写入的目标行位置。
                for (int a = r; a < r + rs; a++) {
                    while (out.size() <= a) out.add(new ArrayList<>());
                    // row：当前数据库记录或表格数据行。
                    var row = out.get(a);
                    while (row.size() < col + cs) row.add(null);
                    // c：展开跨列单元格时当前写入的目标列位置。
                    for (int c = col; c < col + cs; c++) {
                        if (row.get(c) != null) throw protocol();
                        row.set(c, value);
                        chars += value.length();
                        if (chars > max) throw protocol();
                    }
                }
                col += cs;
            }
            r++;
        }
        // 跨行单元格不能凭空扩展出超出原表实际范围的数据行。
        if (out.size() != r) throw protocol();
        // width：图片宽度，单位为像素。
        int width = out.stream().mapToInt(List::size).max().orElse(0);
        if (width == 0) throw protocol();
        return out.stream()
                .map(
                        row -> {
                            // row：当前数据库记录或表格数据行。
                            while (row.size() < width) row.add("");
                            // v：当前表格单元格文本或字段值。
                            return row.stream().map(v -> v == null ? "" : v).toList();
                        })
                .toList();
    }

    /**
     * 读取并限制 HTML 表格跨行或跨列数量。
     *
     * @param value 当前待校验、转换或写入的值。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private int span(String value) {
        if (value.isBlank()) return 1;
        try {
            // n：解析得到的跨行或跨列数量。
            int n = Integer.parseInt(value);
            if (n < 1 || n > config.maxRows()) throw protocol();
            return n;
        } catch (NumberFormatException e) {
            // e：本次捕获或匹配的异常，用于保留业务分类及失败原因。
            throw protocol();
        }
    }

    /**
     * 创建依赖暂不可用的业务异常。
     *
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private static KnowledgeBaseException unavailable() {
        return new KnowledgeBaseException(
                503,
                "KB_MINERU_UNAVAILABLE",
                "PDF fidelity/OCR requires configured MinerU 2.7.6 file_parse service");
    }

    /**
     * 创建远程解析响应不符合约定的异常。
     *
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private static KnowledgeBaseException protocol() {
        return new KnowledgeBaseException(
                502,
                "KB_MINERU_PROTOCOL_ERROR",
                "MinerU deployment/response does not match the configured 2.7.6 content-list contract");
    }

    /**
     * 创建调用预算耗尽的超时异常。
     *
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private static KnowledgeBaseException timeout() {
        return new KnowledgeBaseException(
                504,
                "KB_EXTERNAL_PARSE_TIMEOUT",
                "MinerU exceeded the bounded synchronous request budget; no fallback or unbounded polling");
    }
}
