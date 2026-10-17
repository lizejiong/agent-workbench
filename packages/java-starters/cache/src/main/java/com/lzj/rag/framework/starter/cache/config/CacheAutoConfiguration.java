package com.lzj.rag.framework.starter.cache.config;

import com.lzj.rag.framework.starter.cache.DistributedCache;
import com.lzj.rag.framework.starter.cache.RedisDistributedCache;
import com.lzj.rag.framework.starter.cache.key.RedisKeyBuilder;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis 缓存基础设施自动配置。
 */
@AutoConfiguration(afterName = {
        "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration",
        "org.redisson.spring.starter.RedissonAutoConfiguration",
        "org.redisson.spring.starter.RedissonAutoConfigurationV2"
})
@ConditionalOnClass(StringRedisTemplate.class)
@ConditionalOnBean(StringRedisTemplate.class)
@EnableConfigurationProperties(CacheProperties.class)
public class CacheAutoConfiguration {

    public static final String BLOOM_FILTER_BEAN_NAME = "ragCacheBloomFilter";

    @Bean
    @ConditionalOnMissingBean
    public RedisKeyBuilder redisKeyBuilder(CacheProperties properties) {
        return new RedisKeyBuilder(properties.getKeyPrefix());
    }

    @Bean
    @ConditionalOnMissingBean(DistributedCache.class)
    public RedisDistributedCache distributedCache(
            StringRedisTemplate redisTemplate,
            ObjectProvider<RedissonClient> redissonClientProvider,
            @Qualifier(BLOOM_FILTER_BEAN_NAME) ObjectProvider<RBloomFilter<String>> bloomFilterProvider,
            CacheProperties properties) {
        return new RedisDistributedCache(
                redisTemplate,
                redissonClientProvider.getIfAvailable(),
                bloomFilterProvider.getIfAvailable(),
                properties.getLockKeyPrefix()
        );
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "rag.cache.bloom", name = "enabled", havingValue = "true")
    static class BloomConfiguration {

        @Bean(name = BLOOM_FILTER_BEAN_NAME)
        @ConditionalOnMissingBean(name = BLOOM_FILTER_BEAN_NAME)
        public RBloomFilter<String> ragCacheBloomFilter(
                ObjectProvider<RedissonClient> redissonClientProvider,
                CacheProperties properties) {
            RedissonClient redissonClient = redissonClientProvider.getIfAvailable();
            if (redissonClient == null) {
                throw new IllegalStateException(
                        "rag.cache.bloom.enabled=true requires an application-provided RedissonClient bean"
                );
            }
            CacheProperties.Bloom bloomProperties = properties.getBloom();
            RBloomFilter<String> bloomFilter = redissonClient.getBloomFilter(bloomProperties.getName());
            bloomFilter.tryInit(
                    bloomProperties.getExpectedInsertions(),
                    bloomProperties.getFalsePositiveProbability()
            );
            return bloomFilter;
        }
    }
}
