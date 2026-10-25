package com.lzj.rag.backend.document;

import com.lzj.rag.backend.knowledgebase.KnowledgeBaseException;
import com.lzj.rag.backend.storage.StorageProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import java.nio.file.*;
import java.io.*;
import static org.assertj.core.api.Assertions.*;

class PreparedUploadTest {
    @TempDir Path temp;
    @Test void countsActualStreamBytesAndRemovesSpoolWhenDeclaredSizeLies() throws Exception {
        var file = new MockMultipartFile("file", "doc.txt", "text/plain", new byte[1025]) {
            @Override public long getSize() { return 1; }
            @Override public byte[] getBytes() { throw new AssertionError("Upload must not buffer the full file"); }
        };
        var properties = new StorageProperties(true, null, "us-east-1", "test", true, 1024, temp,
                null, null, null, null, null, 1);
        assertThatThrownBy(() -> PreparedUpload.prepare(file, properties)).isInstanceOfSatisfying(KnowledgeBaseException.class,
                error -> assertThat(error.status()).isEqualTo(413));
        try (var entries = Files.list(temp)) { assertThat(entries).isEmpty(); }
    }
}
