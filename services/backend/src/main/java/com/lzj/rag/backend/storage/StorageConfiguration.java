package com.lzj.rag.backend.storage;

import com.lzj.rag.backend.knowledgebase.KnowledgeBaseException;
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

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StorageProperties.class)
public class StorageConfiguration {
    @Bean(destroyMethod = "close")
    S3Client s3Client(StorageProperties properties) {
        for (Duration duration : new Duration[]{properties.connectTimeout(), properties.socketTimeout(),
                properties.attemptTimeout(), properties.apiTimeout(), properties.uploadLease()}) {
            if (duration == null || duration.isNegative() || duration.isZero()) throw new IllegalStateException("Storage timeouts must be positive");
        }
        if (properties.uploadLease().compareTo(properties.apiTimeout().plusSeconds(30)) <= 0) {
            throw new IllegalStateException("Upload lease must exceed S3 total timeout plus database completion budget");
        }
        if (properties.enabled() && (properties.bucket() == null || properties.bucket().isBlank())) {
            throw new IllegalStateException("KB storage bucket must be configured when storage is enabled");
        }
        var builder = S3Client.builder().region(Region.of(properties.region()))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(properties.pathStyleAccess()).build())
                .httpClientBuilder(ApacheHttpClient.builder().connectionTimeout(properties.connectTimeout())
                        .socketTimeout(properties.socketTimeout()).maxConnections(properties.maxConnections())
                        .connectionAcquisitionTimeout(properties.connectTimeout()))
                .overrideConfiguration(config -> config.apiCallTimeout(properties.apiTimeout())
                        .apiCallAttemptTimeout(properties.attemptTimeout())
                        .retryPolicy(policy -> policy.numRetries(1)));
        if (properties.endpoint() != null && !properties.endpoint().isBlank()) {
            URI endpoint = URI.create(properties.endpoint());
            if (!("https".equals(endpoint.getScheme()) || "http".equals(endpoint.getScheme())) || endpoint.getHost() == null
                    || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null) {
                throw new IllegalStateException("Invalid S3 endpoint");
            }
            builder.endpointOverride(endpoint);
        }
        return builder.build();
    }
    @Bean
    @ConditionalOnMissingBean(ObjectStorageClient.class)
    ObjectStorageClient objectStorageClient(S3Client client, StorageProperties properties) {
        if (properties.enabled()) return new S3ObjectStorageClient(client, properties.bucket());
        return new ObjectStorageClient() {
            private KnowledgeBaseException disabled() { return new KnowledgeBaseException(503, "KB_FEATURE_DISABLED", "Configure and enable S3 storage before uploading"); }
            public void put(String key, Path file, String mime, String hash) { throw disabled(); }
            public InputStream open(String key) { throw disabled(); }
            public void delete(String key) { throw disabled(); }
        };
    }
}
