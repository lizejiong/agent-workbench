package com.lzj.rag.framework.starter.cache.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Redis 缓存组件配置。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "rag.cache")
public class CacheProperties {

    /** 业务缓存 Key 的统一前缀。 */
    private String keyPrefix = "rag";
    /** 防击穿分布式锁的 Key 前缀。 */
    private String lockKeyPrefix = "rag:cache:lock:";
    /** 共享布隆过滤器配置。 */
    private Bloom bloom = new Bloom();

    @Getter
    @Setter
    public static class Bloom {

        /** 是否初始化共享布隆过滤器，启用时须由应用提供 RedissonClient。 */
        private boolean enabled = false;
        /** Redisson 中布隆过滤器的名称。 */
        private String name = "rag:cache:bloom";
        /** 初始化布隆过滤器时预计写入的 Key 数量。 */
        private long expectedInsertions = 1_000_000L;
        /** 允许的误判概率，取值必须在 0 到 1 之间。 */
        private double falsePositiveProbability = 0.01D;
    }
}
