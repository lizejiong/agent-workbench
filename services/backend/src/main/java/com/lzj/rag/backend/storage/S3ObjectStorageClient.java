package com.lzj.rag.backend.storage;

import com.lzj.rag.backend.knowledgebase.KnowledgeBaseException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.Map;

public class S3ObjectStorageClient implements ObjectStorageClient {
    private final S3Client client;
    private final String bucket;
    public S3ObjectStorageClient(S3Client client, String bucket) { this.client = client; this.bucket = bucket; }
    @Override public void put(String key, Path file, String mimeType, String sha256) {
        try {
            client.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType(mimeType)
                    .metadata(Map.of("sha256", sha256)).build(), RequestBody.fromFile(file));
        } catch (RuntimeException error) { throw unavailable(); }
    }
    @Override public InputStream open(String key) {
        try { return client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build()); }
        catch (RuntimeException error) { throw unavailable(); }
    }
    @Override public void delete(String key) {
        try {
            var versioning=client.getBucketVersioning(GetBucketVersioningRequest.builder().bucket(bucket).build()).statusAsString();
            if(versioning!=null && !versioning.isBlank())
                throw new KnowledgeBaseException(503,"KB_STORAGE_VERSIONED_BUCKET","Knowledge files require an unversioned bucket; retained versions need an explicit version-purge policy");
            client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        }
        catch (KnowledgeBaseException error) { throw error; }
        catch (RuntimeException error) { throw unavailable(); }
    }
    private KnowledgeBaseException unavailable() {
        return new KnowledgeBaseException(503, "KB_STORAGE_UNAVAILABLE", "Object storage operation unavailable");
    }
}
