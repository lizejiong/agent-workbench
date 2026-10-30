package com.lzj.rag.backend.parser.support;

import com.lzj.rag.backend.knowledgebase.exception.KnowledgeBaseException;

import org.apache.tika.Tika;
import org.apache.tika.io.TikaInputStream;

import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;

/** 统一处理文件扩展名、真实格式识别、ZIP 安全限制和严格 UTF-8 读取。 */
public final class SupportedFiles {

    /** 禁止实例化仅包含类型定义或静态方法的工具类。 */
    private SupportedFiles() {}

    /** Office 文档格式的 MIME 类型集合。 */
    public static final Set<String> OFFICE =
            Set.of(
                    "application/pdf",
                    "application/msword",
                    "application/vnd.ms-powerpoint",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation");

    /** Excel 工作簿格式的 MIME 类型集合。 */
    public static final Set<String> EXCEL =
            Set.of(
                    "application/vnd.ms-excel",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    /** 支持视觉解析的图片 MIME 类型集合。 */
    public static final Set<String> IMAGES = Set.of("image/png", "image/jpeg");

    /** 纯文本格式的 MIME 类型集合。 */
    private static final Set<String> TEXT =
            Set.of("text/plain", "text/markdown", "text/x-web-markdown");

    /** 扩展名到允许 MIME 类型集合的映射。 */
    private static final Map<String, Set<String>> TYPES =
            Map.ofEntries(
                    Map.entry("txt", TEXT),
                    Map.entry("md", TEXT),
                    Map.entry("markdown", TEXT),
                    Map.entry("csv", Set.of("text/plain", "text/csv", "application/csv")),
                    Map.entry("pdf", Set.of("application/pdf")),
                    Map.entry("doc", Set.of("application/msword")),
                    Map.entry(
                            "docx",
                            Set.of(
                                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document")),
                    Map.entry("ppt", Set.of("application/vnd.ms-powerpoint")),
                    Map.entry(
                            "pptx",
                            Set.of(
                                    "application/vnd.openxmlformats-officedocument.presentationml.presentation")),
                    Map.entry("xls", Set.of("application/vnd.ms-excel")),
                    Map.entry(
                            "xlsx",
                            Set.of(
                                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")),
                    Map.entry("png", Set.of("image/png")),
                    Map.entry("jpg", Set.of("image/jpeg")),
                    Map.entry("jpeg", Set.of("image/jpeg")));

    /**
     * 提取并规范化文件扩展名。
     *
     * @param filename 经过业务校验的文件名。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public static String extension(String filename) {
        // i：当前遍历位置，按原始输入顺序递增。
        int i = filename.lastIndexOf('.');
        return i < 0 ? "" : filename.substring(i + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * 按输入类型判断文本格式或在字符预算内读取严格 UTF-8 内容。
     *
     * @param filename 经过业务校验的文件名。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public static boolean text(String filename) {
        return Set.of("txt", "md", "markdown", "csv").contains(extension(filename));
    }

    /**
     * 判断文件扩展名是否位于业务允许的格式集合。
     *
     * @param filename 经过业务校验的文件名。
     */
    public static void extensionAllowed(String filename) {
        if (!TYPES.containsKey(extension(filename))) throw unsupported();
    }

    /**
     * 读取文件真实内容识别格式，不以客户端声明的类型作为唯一依据。
     *
     * @param file 待校验、读取或写入的源文件。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public static String detect(Path file) throws IOException {
        // in：当前文件或响应的输入流，使用结束后关闭。
        // 格式识别可能读取 ZIP 条目，先检查解压规模再交给 Tika。
        try (var in = Files.newInputStream(file)) {
            // magic：用于真实格式识别的文件头字节。
            byte[] magic = in.readNBytes(4);
            if (magic.length == 4 && magic[0] == 'P' && magic[1] == 'K') checkZip(file);
        }
        // in：当前文件或响应的输入流，使用结束后关闭。
        try (var in = TikaInputStream.get(file)) {
            return new Tika().detect(in);
        }
    }

    /**
     * 校验扩展名与实际 MIME 类型相互兼容。
     *
     * @param filename 经过业务校验的文件名。
     * @param mime 识别或选择的文件 MIME 类型。
     */
    public static void compatible(String filename, String mime) {
        if (!TYPES.getOrDefault(extension(filename), Set.of()).contains(mime)) throw unsupported();
    }

    /**
     * 检查 ZIP 容器的条目及解压规模，防止异常压缩内容进入解析器。
     *
     * @param file 待校验、读取或写入的源文件。
     */
    private static void checkZip(Path file) throws IOException {
        // zip：当前需要校验的 ZIP 容器。
        try (var zip = new ZipFile(file.toFile())) {
            // entries：当前需要遍历的 JSON 或配置条目集合。
            var entries = zip.entries();
            // total：满足当前查询条件的记录总数。
            long total = 0;
            // count：当前累计数量或本次流读取的有效字节数。
            int count = 0;
            while (entries.hasMoreElements()) {
                // entry：当前处理的键值项。
                var entry = entries.nextElement();
                // size：分页容量或当前数据的字节数。
                long size = entry.getSize();
                // compressed：压缩条目的原始压缩字节数。
                long compressed = entry.getCompressedSize();
                if (++count > 4096
                        || size < 0
                        || (total += size) > 67108864
                        || size > 16777216
                        || (size > 1048576
                                && (compressed <= 0 || (double) size / compressed > 100)))
                    throw new KnowledgeBaseException(
                            422,
                            "KB_CONTAINER_LIMIT",
                            "Office archive exceeds bounded expansion limits");
            }
        }
    }

    /**
     * 判断源文件是否能以严格 UTF-8 解码。
     *
     * @param file 待校验、读取或写入的源文件。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public static Reader utf8(Path file) throws IOException {
        // decoder：对无效或不完整输入明确报错的 UTF-8 解码器。
        var decoder =
                StandardCharsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT);
        return new InputStreamReader(Files.newInputStream(file), decoder);
    }

    /**
     * 按输入类型判断文本格式或在字符预算内读取严格 UTF-8 内容。
     *
     * @param file 待校验、读取或写入的源文件。
     * @param max 当前校验允许的最大长度或数值。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public static String text(Path file, int max) throws IOException {
        // text：当前待处理或生成的文本内容。
        var text = new StringBuilder();
        // reader：当前受限文本或图片读取器。
        try (var reader = utf8(file)) {
            // b：当前读取或待处理的字符缓冲区。
            char[] b = new char[4096];
            // n：本次流读取返回的有效字节或字符数量。
            int n;
            while ((n = reader.read(b)) != -1) {
                if (text.length() + n > max)
                    throw new KnowledgeBaseException(
                            422, "KB_SOURCE_TOO_LARGE", "Text exceeds parsed character budget");
                text.append(b, 0, n);
            }
        }
        // value：当前待校验、转换或写入的值。
        String value = text.toString();
        if (value.startsWith("\ufeff")) value = value.substring(1);
        return value.replace("\r\n", "\n").replace('\r', '\n');
    }

    /**
     * 创建文件格式不受支持的异常。
     *
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private static KnowledgeBaseException unsupported() {
        return new KnowledgeBaseException(
                415,
                "KB_UNSUPPORTED_FILE",
                "File extension and detected media type must match the supported format matrix");
    }
}
