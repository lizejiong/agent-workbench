package com.lzj.rag.backend.document.upload;

import com.lzj.rag.backend.document.service.bo.UploadSourceBO;
import com.lzj.rag.backend.infra.storage.config.StorageProperties;
import com.lzj.rag.backend.knowledgebase.exception.KnowledgeBaseException;
import com.lzj.rag.backend.parser.support.SupportedFiles;

import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** 已完成大小、摘要及格式校验的临时上传文件；关闭时回收磁盘文件。 */
public final class PreparedUpload implements AutoCloseable {

    /** 当前需要读取、写入或清理的文件路径。 */
    public final Path path;

    /** 经过业务校验的文件名。 */
    public final String filename;

    /** 已校验源文件的实际字节数。 */
    public final long size;

    /** 源内容或切片正文的稳定摘要。 */
    public final String hash;

    /** 识别或选择的文件 MIME 类型。 */
    public final String mime;

    /**
     * 初始化当前组件，保存业务依赖及本次使用的配置。
     *
     * @param path 当前需要读取、写入或清理的文件路径。
     * @param filename 经过业务校验的文件名。
     * @param size 已校验源文件的实际字节数。
     * @param hash 源内容或切片正文的稳定摘要。
     * @param mime 识别或选择的文件 MIME 类型。
     */
    private PreparedUpload(Path path, String filename, long size, String hash, String mime) {
        this.path = path;
        this.filename = filename;
        this.size = size;
        this.hash = hash;
        this.mime = mime;
    }

    /**
     * 将上传流复制到有界临时文件，计算摘要并检查扩展名与真实格式。
     *
     * @param file 调用方上传的文件输入，后续按流读取并检查大小及格式。
     * @param properties 对象存储连接、文件容量、临时目录及上传补偿配置。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public static PreparedUpload prepare(UploadSourceBO file, StorageProperties properties) {
        // filename：经过业务校验的文件名。
        String filename = file.getOriginalFilename();
        if (filename == null) throw invalid("File name is required");
        filename = filename.replace('\\', '/');
        filename = safeName(filename.substring(filename.lastIndexOf('/') + 1));
        SupportedFiles.extensionAllowed(filename);
        if (file.isEmpty())
            throw new KnowledgeBaseException(400, "KB_EMPTY_FILE", "File must not be empty");
        if (file.getSize() > properties.maxUploadBytes()) throw tooLarge();
        // spool：当前尝试持有的磁盘临时文件。
        Path spool = null;
        try {
            // directory：临时文件所在目录。
            Path directory = properties.tempDirectory();
            if (directory != null && !directory.toString().isBlank()) {
                Files.createDirectories(directory);
                spool = Files.createTempFile(directory, "kb-upload-", ".tmp");
            } else spool = Files.createTempFile("kb-upload-", ".tmp");
            // digest：用于流式累计 SHA-256 摘要的计算器。
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            // count：当前累计数量或本次流读取的有效字节数。
            long count = 0;
            // out：当前需要关闭的文件输出流。
            // in：当前文件或响应的输入流，使用结束后关闭。
            try (InputStream in = file.getInputStream();
                    OutputStream out = Files.newOutputStream(spool)) {
                // buffer：用于分块读写的固定大小缓冲区。
                byte[] buffer = new byte[8192];
                // n：本次流读取返回的有效字节或字符数量。
                int n;
                while ((n = in.read(buffer)) != -1) {
                    count += n;
                    if (count > properties.maxUploadBytes()) throw tooLarge();
                    digest.update(buffer, 0, n);
                    out.write(buffer, 0, n);
                }
            }
            if (count == 0)
                throw new KnowledgeBaseException(400, "KB_EMPTY_FILE", "File must not be empty");
            // mime：识别或选择的文件 MIME 类型。
            String mime = SupportedFiles.detect(spool);
            SupportedFiles.compatible(filename, mime);
            if (SupportedFiles.text(filename)) {
                // decoder：对无效或不完整输入明确报错的 UTF-8 解码器。
                CharsetDecoder decoder =
                        StandardCharsets.UTF_8
                                .newDecoder()
                                .onMalformedInput(CodingErrorAction.REPORT)
                                .onUnmappableCharacter(CodingErrorAction.REPORT);
                // reader：当前受限文本或图片读取器。
                try (Reader reader = new InputStreamReader(Files.newInputStream(spool), decoder)) {
                    // buffer：用于分块读写的固定大小缓冲区。
                    char[] buffer = new char[4096];
                    // n：本次流读取返回的有效字节或字符数量。
                    int n;
                    while ((n = reader.read(buffer)) != -1) /*
 * i：当前遍历位置，按原始输入顺序递增。
 */
                        for (int i = 0; i < n; i++) {
                            // ch：当前处理的字符或原始字符缓冲区。
                            char ch = buffer[i];
                            if (Character.isISOControl(ch)
                                    && ch != '\n'
                                    && ch != '\r'
                                    && ch != '\t') {
                                throw new KnowledgeBaseException(
                                        415,
                                        "KB_UNSUPPORTED_FILE",
                                        "File contains binary control characters");
                            }
                        }
                }
            }
            return new PreparedUpload(
                    spool, filename, count, HexFormat.of().formatHex(digest.digest()), mime);
        } catch (CharacterCodingException e) {
            // e：本次捕获或匹配的异常，用于保留业务分类及失败原因。
            remove(spool);
            throw new KnowledgeBaseException(
                    415, "KB_UNSUPPORTED_FILE", "File must use UTF-8 encoding");
        } catch (IOException | NoSuchAlgorithmException e) {
            // e：本次捕获或匹配的异常，用于保留业务分类及失败原因。
            remove(spool);
            throw new KnowledgeBaseException(
                    503, "KB_UPLOAD_IO_UNAVAILABLE", "Upload staging is unavailable");
        } catch (RuntimeException e) {
            // e：本次捕获或匹配的异常，用于保留业务分类及失败原因。
            remove(spool);
            throw e;
        }
    }

    /**
     * 提取并校验安全文件名，拒绝路径片段及控制字符。
     *
     * @param input 本阶段需要处理的输入内容或固定输入快照。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public static String safeName(String input) {
        // value：当前待校验、转换或写入的值。
        String value = input.strip();
        if (value.isEmpty()
                || value.length() > 255
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid("Invalid file or document name");
        }
        return value;
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

    /**
     * 创建结构或文件超过配置容量的异常。
     *
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private static KnowledgeBaseException tooLarge() {
        return new KnowledgeBaseException(
                413, "KB_UPLOAD_TOO_LARGE", "File exceeds configured upload limit");
    }

    /**
     * 尽力删除临时文件，删除失败时留给后台清理。
     *
     * @param path 当前需要读取、写入或清理的文件路径。
     */
    private static void remove(Path path) {
        if (path != null)
            try {
                Files.deleteIfExists(path);
            } catch (IOException e) {
                // e：本次捕获或匹配的异常，用于保留业务分类及失败原因。
                org.slf4j.LoggerFactory.getLogger(PreparedUpload.class)
                        .warn("Upload spool cleanup failed");
            }
    }

    /** 释放当前对象持有的临时文件或执行资源。 */
    @Override
    public void close() {
        remove(path);
    }
}
