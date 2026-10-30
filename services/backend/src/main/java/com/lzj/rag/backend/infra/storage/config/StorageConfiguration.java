package com.lzj.rag.backend.infra.storage.config;

import com.lzj.rag.backend.infra.storage.ObjectStorageClient;
import com.lzj.rag.backend.infra.storage.S3ObjectStorageClient;
import com.lzj.rag.backend.infra.storage.exception.ObjectStorageException;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

/** 装配受限 S3 客户端，未启用存储时提供明确失败的实现。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StorageProperties.class)
public class StorageConfiguration {

    /**
     * 按配置创建带连接、调用预算及并发限制的 S3 客户端。
     *
     * @param properties 对象存储连接、文件容量、临时目录及上传补偿配置。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @Bean(destroyMethod = "close")
    S3Client s3Client(StorageProperties properties) {
        // duration：当前计算得到的时间间隔。
        for (Duration duration :
                new Duration[] {
                    properties.connectTimeout(),
                    properties.socketTimeout(),
                    properties.attemptTimeout(),
                    properties.apiTimeout(),
                    properties.uploadLease()
                }) {
            if (duration == null || duration.isNegative() || duration.isZero())
                throw new IllegalStateException("Storage timeouts must be positive");
        }
        if (properties.uploadLease().compareTo(properties.apiTimeout().plusSeconds(30)) <= 0) {
            throw new IllegalStateException(
                    "Upload lease must exceed S3 total timeout plus database completion budget");
        }
        if (properties.enabled()
                && (properties.bucket() == null || properties.bucket().isBlank())) {
            throw new IllegalStateException(
                    "KB storage bucket must be configured when storage is enabled");
        }
        // policy：当前 HTTP 安全规则的配置回调。
        // config：当前组件使用的配置或底层客户端参数构建器。
        // builder：当前组件或请求的参数构建器。
        var builder =
                S3Client.builder()
                        .region(Region.of(properties.region()))
                        .credentialsProvider(DefaultCredentialsProvider.create())
                        .serviceConfiguration(
                                S3Configuration.builder()
                                        .pathStyleAccessEnabled(properties.pathStyleAccess())
                                        .build())
                        .httpClientBuilder(
                                ApacheHttpClient.builder()
                                        .connectionTimeout(properties.connectTimeout())
                                        .socketTimeout(properties.socketTimeout())
                                        .maxConnections(properties.maxConnections())
                                        .connectionAcquisitionTimeout(properties.connectTimeout()))
                        .overrideConfiguration(
                                config ->
                                        config.apiCallTimeout(properties.apiTimeout())
                                                .apiCallAttemptTimeout(properties.attemptTimeout())
                                                .retryPolicy(policy -> policy.numRetries(1)));
        if (properties.endpoint() != null && !properties.endpoint().isBlank()) {
            // endpoint：外部服务连接地址。
            URI endpoint = URI.create(properties.endpoint());
            if (!("https".equals(endpoint.getScheme()) || "http".equals(endpoint.getScheme()))
                    || endpoint.getHost() == null
                    || endpoint.getUserInfo() != null
                    || endpoint.getQuery() != null
                    || endpoint.getFragment() != null) {
                throw new IllegalStateException("Invalid S3 endpoint");
            }
            builder.endpointOverride(endpoint);
        }
        return builder.build();
    }

    /**
     * 存储启用时装配 S3 实现，否则返回明确报告不可用的实现。
     *
     * @param client 配置了连接及调用预算的 S3 客户端。
     * @param properties 对象存储连接、文件容量、临时目录及上传补偿配置。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @Bean
    @ConditionalOnMissingBean(ObjectStorageClient.class)
    ObjectStorageClient objectStorageClient(S3Client client, StorageProperties properties) {
        if (properties.enabled()) return new S3ObjectStorageClient(client, properties.bucket());
        return new ObjectStorageClient() {

            /**
             * 创建对象存储未启用时的业务异常。
             *
             * @return 当前业务处理结果；失败时通过异常报告。
             */
            private ObjectStorageException disabled() {
                return new ObjectStorageException(
                        "KB_FEATURE_DISABLED", "Configure and enable S3 storage before uploading");
            }

            /**
             * 拒绝未启用对象存储时的写入请求。
             *
             * @param key 对象存储键、配置键或业务幂等键。
             * @param file 待校验、读取或写入的源文件。
             * @param mime 识别或选择的文件 MIME 类型。
             * @param hash 源内容或切片正文的稳定摘要。
             */
            public void put(String key, Path file, String mime, String hash) {
                throw disabled();
            }

            /**
             * 拒绝未启用对象存储时的读取请求。
             *
             * @param key 对象存储键、配置键或业务幂等键。
             * @return 当前业务处理结果；失败时通过异常报告。
             */
            public InputStream open(String key) {
                throw disabled();
            }

            /**
             * 拒绝未启用对象存储时的删除请求。
             *
             * @param key 对象存储键、配置键或业务幂等键。
             */
            public void delete(String key) {
                throw disabled();
            }
        };
    }
}
