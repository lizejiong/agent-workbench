package com.lzj.rag.backend.infra.storage.config;

import jakarta.validation.constraints.*;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;
import java.time.Duration;

/**
 * 对象存储连接、文件容量、临时目录及上传补偿配置。
 *
 * @param enabled 是否启用该资源或能力。
 * @param endpoint 外部服务连接地址。
 * @param region S3 客户端使用的区域名称。
 * @param bucket 源文件对象存储桶名称。
 * @param pathStyleAccess 是否使用 S3 路径式桶访问方式。
 * @param maxUploadBytes 源文件上传字节数上限。
 * @param tempDirectory 可配置的临时文件目录。
 * @param connectTimeout 建立外部连接的时间预算。
 * @param socketTimeout 底层套接字读取超时。
 * @param attemptTimeout 单次底层请求尝试的时间预算。
 * @param apiTimeout 外部服务 API 调用预算。
 * @param uploadLease 上传操作预留记录的有效租约时长。
 * @param maxConnections 允许同时建立的连接数量上限。
 */
@Validated
@ConfigurationProperties("kb.storage")
public record StorageProperties(
        @DefaultValue("false") boolean enabled,
        String endpoint,
        @DefaultValue("us-east-1") @NotBlank String region,
        String bucket,
        @DefaultValue("false") boolean pathStyleAccess,
        @DefaultValue("20971520") @Min(1) @Max(1073741824) long maxUploadBytes,
        Path tempDirectory,
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("30s") Duration socketTimeout,
        @DefaultValue("40s") Duration attemptTimeout,
        @DefaultValue("90s") Duration apiTimeout,
        @DefaultValue("5m") Duration uploadLease,
        @DefaultValue("8") @Min(1) int maxConnections) {}
