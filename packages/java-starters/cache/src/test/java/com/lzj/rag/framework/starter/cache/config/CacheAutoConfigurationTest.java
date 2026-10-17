package com.lzj.rag.framework.starter.cache.config;

import com.lzj.rag.framework.starter.cache.DistributedCache;
import com.lzj.rag.framework.starter.cache.RedisDistributedCache;
import com.lzj.rag.framework.starter.cache.key.RedisKeyBuilder;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class CacheAutoConfigurationTest {

    private static final String BLOOM_FILTER_BEAN_NAME = CacheAutoConfiguration.BLOOM_FILTER_BEAN_NAME;

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CacheAutoConfiguration.class));

    @Test
    void shouldConfigureCacheInfrastructureAndBindProperties() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        RedissonClient redissonClient = mock(RedissonClient.class);
        RBloomFilter<String> bloomFilter = mock(RBloomFilter.class);
        when(redissonClient.<String>getBloomFilter("ticket:bloom")).thenReturn(bloomFilter);

        contextRunner
                .withBean(StringRedisTemplate.class, () -> redisTemplate)
                .withBean(RedissonClient.class, () -> redissonClient)
                .withPropertyValues(
                        "rag.cache.key-prefix=ticketing",
                        "rag.cache.lock-key-prefix=ticket:lock:",
                        "rag.cache.bloom.enabled=true",
                        "rag.cache.bloom.name=ticket:bloom",
                        "rag.cache.bloom.expected-insertions=2000",
                        "rag.cache.bloom.false-positive-probability=0.02"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(CacheProperties.class);
                    assertThat(context).hasSingleBean(RedisKeyBuilder.class);
                    assertThat(context).hasSingleBean(DistributedCache.class);
                    assertThat(context.getBean(DistributedCache.class))
                            .isInstanceOf(RedisDistributedCache.class);
                    assertThat(context.getBean(RedisKeyBuilder.class).build("train", "G123"))
                            .isEqualTo("ticketing:train:G123");
                    assertThat(context).getBean("ragCacheBloomFilter").isSameAs(bloomFilter);
                    verify(bloomFilter).tryInit(2_000L, 0.02D);
                });
    }

    @Test
    void shouldConfigurePlainCacheWithOnlyStringRedisTemplate() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);

        contextRunner
                .withBean(StringRedisTemplate.class, () -> redisTemplate)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(DistributedCache.class);
                    assertThat(context).hasSingleBean(RedisKeyBuilder.class);
                    assertThat(context).doesNotHaveBean(BLOOM_FILTER_BEAN_NAME);
                    assertThat(context.getBean(DistributedCache.class).getRedisTemplate())
                            .isSameAs(redisTemplate);
                    assertThat(context.getBean(DistributedCache.class).getRedissonClient()).isNull();
                    assertThat(context.getBean(CacheProperties.class).getBloom().isEnabled()).isFalse();
                });
    }

    @Test
    void shouldNotInitializeBloomFilterByDefaultWhenRedissonIsAvailable() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        RedissonClient redissonClient = mock(RedissonClient.class);

        contextRunner
                .withBean(StringRedisTemplate.class, () -> redisTemplate)
                .withBean(RedissonClient.class, () -> redissonClient)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(DistributedCache.class);
                    assertThat(context).doesNotHaveBean(BLOOM_FILTER_BEAN_NAME);
                    assertThat(context.getBean(DistributedCache.class).getRedissonClient())
                            .isSameAs(redissonClient);
                    verifyNoInteractions(redissonClient);
                });
    }

    @Test
    void shouldUseApplicationBloomFilterWithoutEnablingInitialization() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        RBloomFilter<String> bloomFilter = mock(RBloomFilter.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        contextRunner
                .withBean(StringRedisTemplate.class, () -> redisTemplate)
                .withBean(BLOOM_FILTER_BEAN_NAME, RBloomFilter.class, () -> bloomFilter)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).getBean(BLOOM_FILTER_BEAN_NAME).isSameAs(bloomFilter);
                    verifyNoInteractions(bloomFilter);

                    context.getBean(DistributedCache.class).safePut("train:G123", "available");

                    verify(valueOperations).set("train:G123", "\"available\"");
                    verify(bloomFilter).add("train:G123");
                });
    }

    @Test
    void shouldFailWhenBloomIsEnabledWithoutApplicationRedissonClient() {
        contextRunner
                .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
                .withPropertyValues("rag.cache.bloom.enabled=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .hasRootCauseMessage(
                                    "rag.cache.bloom.enabled=true requires an application-provided RedissonClient bean"
                            );
                });
    }

    @Test
    void shouldBackOffWhenBloomIsEnabledWithApplicationBloomAndNoRedissonClient() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        RBloomFilter<String> bloomFilter = mock(RBloomFilter.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        contextRunner
                .withBean(StringRedisTemplate.class, () -> redisTemplate)
                .withBean(BLOOM_FILTER_BEAN_NAME, RBloomFilter.class, () -> bloomFilter)
                .withPropertyValues("rag.cache.bloom.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).getBean(BLOOM_FILTER_BEAN_NAME).isSameAs(bloomFilter);
                    assertThat(context.getBean(DistributedCache.class).getRedissonClient()).isNull();
                    verifyNoInteractions(bloomFilter);

                    context.getBean(DistributedCache.class).safePut("train:G123", "available");

                    verify(valueOperations).set("train:G123", "\"available\"");
                    verify(bloomFilter).add("train:G123");
                });
    }

    @Test
    void shouldStayDisabledWhenRedisClientIsMissing() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(DistributedCache.class);
            assertThat(context).doesNotHaveBean(RedisKeyBuilder.class);
        });
    }

    @Test
    void shouldBackOffForApplicationBeans() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        RedissonClient redissonClient = mock(RedissonClient.class);
        RBloomFilter<String> bloomFilter = mock(RBloomFilter.class);
        DistributedCache customCache = mock(DistributedCache.class);
        RedisKeyBuilder customKeyBuilder = new RedisKeyBuilder("custom");

        contextRunner
                .withBean(StringRedisTemplate.class, () -> redisTemplate)
                .withBean(RedissonClient.class, () -> redissonClient)
                .withBean("ragCacheBloomFilter", RBloomFilter.class, () -> bloomFilter)
                .withBean(DistributedCache.class, () -> customCache)
                .withBean(RedisKeyBuilder.class, () -> customKeyBuilder)
                .withPropertyValues("rag.cache.bloom.enabled=true")
                .run(context -> {
                    assertThat(context.getBean(DistributedCache.class)).isSameAs(customCache);
                    assertThat(context.getBean(RedisKeyBuilder.class)).isSameAs(customKeyBuilder);
                    assertThat(context.getBean("ragCacheBloomFilter")).isSameAs(bloomFilter);
                    verifyNoInteractions(redissonClient, bloomFilter);
                });
    }
}
