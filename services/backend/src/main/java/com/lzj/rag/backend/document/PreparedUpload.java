package com.lzj.rag.backend.document;

import com.lzj.rag.backend.knowledgebase.KnowledgeBaseException;
import com.lzj.rag.backend.storage.StorageProperties;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Bounded disk spool: neither MIME detection nor hashing loads the whole upload into memory. */
public final class PreparedUpload implements AutoCloseable {
    public final Path path;
    public final String filename;
    public final long size;
    public final String hash;
    public final String mime;

    private PreparedUpload(Path path, String filename, long size, String hash, String mime) {
        this.path = path; this.filename = filename; this.size = size; this.hash = hash; this.mime = mime;
    }
    public static PreparedUpload prepare(MultipartFile file, StorageProperties properties) {
        String filename = file.getOriginalFilename();
        if (filename == null) throw invalid("File name is required");
        filename = filename.replace('\\', '/');
        filename = safeName(filename.substring(filename.lastIndexOf('/') + 1));
        com.lzj.rag.backend.parser.SupportedFiles.extensionAllowed(filename);
        if (file.isEmpty()) throw new KnowledgeBaseException(400, "KB_EMPTY_FILE", "File must not be empty");
        if (file.getSize() > properties.maxUploadBytes()) throw tooLarge();
        Path spool = null;
        try {
            Path directory = properties.tempDirectory();
            if (directory != null && !directory.toString().isBlank()) {
                Files.createDirectories(directory);
                spool = Files.createTempFile(directory, "kb-upload-", ".tmp");
            } else spool = Files.createTempFile("kb-upload-", ".tmp");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long count = 0;
            try (InputStream in = file.getInputStream(); OutputStream out = Files.newOutputStream(spool)) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    count += n;
                    if (count > properties.maxUploadBytes()) throw tooLarge();
                    digest.update(buffer, 0, n); out.write(buffer, 0, n);
                }
            }
            if (count == 0) throw new KnowledgeBaseException(400, "KB_EMPTY_FILE", "File must not be empty");
            String mime=com.lzj.rag.backend.parser.SupportedFiles.detect(spool);
            com.lzj.rag.backend.parser.SupportedFiles.compatible(filename,mime);
            if(com.lzj.rag.backend.parser.SupportedFiles.text(filename)) {
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
            try (Reader reader = new InputStreamReader(Files.newInputStream(spool), decoder)) {
                char[] buffer = new char[4096]; int n;
                while ((n = reader.read(buffer)) != -1) for (int i = 0; i < n; i++) {
                    char ch = buffer[i];
                    if (Character.isISOControl(ch) && ch != '\n' && ch != '\r' && ch != '\t') {
                        throw new KnowledgeBaseException(415, "KB_UNSUPPORTED_FILE", "File contains binary control characters");
                    }
                }
            }
            }
            return new PreparedUpload(spool, filename, count, HexFormat.of().formatHex(digest.digest()), mime);
        } catch (CharacterCodingException e) {
            remove(spool);
            throw new KnowledgeBaseException(415, "KB_UNSUPPORTED_FILE", "File must use UTF-8 encoding");
        } catch (IOException | NoSuchAlgorithmException e) {
            remove(spool);
            throw new KnowledgeBaseException(503, "KB_UPLOAD_IO_UNAVAILABLE", "Upload staging is unavailable");
        } catch (RuntimeException e) { remove(spool); throw e; }
    }
    static String safeName(String input) {
        String value = input.strip();
        if (value.isEmpty() || value.length() > 255 || value.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid("Invalid file or document name");
        }
        return value;
    }
    private static KnowledgeBaseException invalid(String message) { return new KnowledgeBaseException(400, "KB_INVALID_INPUT", message); }
    private static KnowledgeBaseException tooLarge() { return new KnowledgeBaseException(413, "KB_UPLOAD_TOO_LARGE", "File exceeds configured upload limit"); }
    private static void remove(Path path) {
        if (path != null) try { Files.deleteIfExists(path); }
        catch (IOException e) { org.slf4j.LoggerFactory.getLogger(PreparedUpload.class).warn("Upload spool cleanup failed"); }
    }
    @Override public void close() { remove(path); }
}
