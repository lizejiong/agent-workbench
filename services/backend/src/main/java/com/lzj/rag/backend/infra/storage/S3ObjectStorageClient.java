package com.lzj.rag.backend.infra.storage;

import com.lzj.rag.backend.infra.storage.exception.ObjectStorageException;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.Map;

/** 基于 S3 的源文件存储适配，将存储错误归一化为业务错误。 */
public class S3ObjectStorageClient implements ObjectStorageClient {

    /** 配置了连接及调用预算的 S3 客户端。 */
    private final S3Client client;

    /** 源文件对象存储桶名称。 */
    private final String bucket;

    /**
     * 初始化当前组件，保存业务依赖及本次使用的配置。
     *
     * @param client 配置了连接及调用预算的 S3 客户端。
     * @param bucket 源文件对象存储桶名称。
     */
    public S3ObjectStorageClient(S3Client client, String bucket) {
        this.client = client;
        this.bucket = bucket;
    }

    /**
     * 将已校验临时文件写入指定对象键，并保留内容类型和摘要信息。
     *
     * @param key 对象存储键、配置键或业务幂等键。
     * @param file 待校验、读取或写入的源文件。
     * @param mimeType 源文件 MIME 类型。
     * @param sha256 文件 SHA-256 摘要的十六进制表示。
     */
    @Override
    public void put(String key, Path file, String mimeType, String sha256) {
        try {
            client.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .contentType(mimeType)
                            .metadata(Map.of("sha256", sha256))
                            .build(),
                    RequestBody.fromFile(file));
        } catch (RuntimeException error) {
            // error：本次处理捕获的异常，用于归类失败原因。
            throw unavailable();
        }
    }

    /**
     * 打开源对象的输入流，由调用方负责关闭。
     *
     * @param key 对象存储键、配置键或业务幂等键。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @Override
    public InputStream open(String key) {
        try {
            return client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (RuntimeException error) {
            // error：本次处理捕获的异常，用于归类失败原因。
            throw unavailable();
        }
    }

    /**
     * 幂等删除指定源对象，已不存在时视为完成。
     *
     * @param key 对象存储键、配置键或业务幂等键。
     */
    @Override
    public void delete(String key) {
        try {
            // versioning：存储桶版本保留状态；当前清理策略只支持未开启版本保留的桶。
            var versioning =
                    client.getBucketVersioning(
                                    GetBucketVersioningRequest.builder().bucket(bucket).build())
                            .statusAsString();
            if (versioning != null && !versioning.isBlank())
                throw new ObjectStorageException(
                        "KB_STORAGE_VERSIONED_BUCKET",
                        "Knowledge files require an unversioned bucket; retained versions need an explicit version-purge policy");
            client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (ObjectStorageException error) {
            // error：本次处理捕获的异常，用于归类失败原因。
            throw error;
        } catch (RuntimeException error) {
            // error：本次处理捕获的异常，用于归类失败原因。
            throw unavailable();
        }
    }

    /**
     * 创建依赖暂不可用的业务异常。
     *
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private ObjectStorageException unavailable() {
        return new ObjectStorageException(
                "KB_STORAGE_UNAVAILABLE", "Object storage operation unavailable");
    }
}
