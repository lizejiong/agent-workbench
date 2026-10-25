package com.lzj.rag.backend.storage;

import java.io.InputStream;
import java.nio.file.Path;

public interface ObjectStorageClient {
    void put(String objectKey, Path file, String mimeType, String sha256);
    InputStream open(String objectKey);
    /** Idempotent, including objects that do not exist. */
    void delete(String objectKey);
}
