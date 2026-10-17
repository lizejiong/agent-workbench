package com.lzj.rag.framework.starter.cache;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class RedisDistributedCacheTest {

    private static final Duration TTL = Duration.ofMinutes(10);

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RBloomFilter<String> bloomFilter;
    @Mock
    private RLock lock;

    private RedisDistributedCache cache;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        cache = new RedisDistributedCache(redisTemplate, redissonClient, bloomFilter, "rag:cache:lock:");
    }

    @Test
    void shouldReadWriteAndDeleteJsonValues() {
        Train train = new Train("G123", "Beijing");
        when(valueOperations.get("train:G123")).thenReturn(JSON.toJSONString(train));
        when(redisTemplate.delete("train:G123")).thenReturn(true);

        assertThat(cache.get("train:G123", Train.class)).isEqualTo(train);
        cache.put("train:G123", train);
        cache.put("train:G123", train, TTL);
        assertThat(cache.delete("train:G123")).isTrue();

        verify(valueOperations).set("train:G123", JSON.toJSONString(train));
        verify(valueOperations).set("train:G123", JSON.toJSONString(train), TTL);
    }

    @Test
    void shouldReadAndWriteWithOnlyRedisTemplate() {
        cache = new RedisDistributedCache(redisTemplate, null, null, "rag:cache:lock:");
        Train train = new Train("G123", "Beijing");
        when(valueOperations.get("train:G123")).thenReturn(JSON.toJSONString(train));

        assertThat(cache.get("train:G123", Train.class)).isEqualTo(train);
        cache.put("train:G123", train);
        cache.put("train:G123", train, TTL);

        verify(valueOperations).set("train:G123", JSON.toJSONString(train));
        verify(valueOperations).set("train:G123", JSON.toJSONString(train), TTL);
    }

    @Test
    void shouldLoadAndCacheWithOnlyRedisTemplate() {
        cache = new RedisDistributedCache(redisTemplate, null, null, "rag:cache:lock:");
        Train train = new Train("G123", "Beijing");
        Supplier<Train> loader = org.mockito.Mockito.mock(Supplier.class);
        when(loader.get()).thenReturn(train);

        assertThat(cache.getOrLoad("train:G123", Train.class, loader, TTL)).isEqualTo(train);

        verify(loader).get();
        verify(valueOperations).set("train:G123", JSON.toJSONString(train), TTL);
    }

    @Test
    void shouldFailSafeReadWithoutRedissonBeforeAccessingRedis() {
        cache = new RedisDistributedCache(redisTemplate, null, bloomFilter, "rag:cache:lock:");
        Supplier<Train> loader = org.mockito.Mockito.mock(Supplier.class);

        assertThatThrownBy(() -> cache.safeGet("train:G123", Train.class, loader, TTL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("safeGet")
                .hasMessageContaining("RedissonClient");

        verify(redisTemplate, never()).opsForValue();
        verifyNoInteractions(bloomFilter, loader);
    }

    @Test
    void shouldFailSafeReadWithoutBloomBeforeAccessingRedis() {
        cache = new RedisDistributedCache(redisTemplate, redissonClient, null, "rag:cache:lock:");
        Supplier<Train> loader = org.mockito.Mockito.mock(Supplier.class);

        assertThatThrownBy(() -> cache.safeGet("train:G123", Train.class, loader, TTL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("safeGet")
                .hasMessageContaining("rag.cache.bloom.enabled=true");

        verify(redisTemplate, never()).opsForValue();
        verifyNoInteractions(redissonClient, loader);
    }

    @Test
    void shouldFailSafeWritesWithoutBloomBeforeWritingRedis() {
        cache = new RedisDistributedCache(redisTemplate, null, null, "rag:cache:lock:");
        Train train = new Train("G123", "Beijing");

        assertThatThrownBy(() -> cache.safePut("train:G123", train))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("safePut")
                .hasMessageContaining("rag.cache.bloom.enabled=true");
        assertThatThrownBy(() -> cache.safePut("train:G123", train, TTL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("safePut")
                .hasMessageContaining("rag.cache.bloom.enabled=true");

        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    void shouldSafelyWriteWithApplicationBloomAndNoRedissonClient() {
        cache = new RedisDistributedCache(redisTemplate, null, bloomFilter, "rag:cache:lock:");
        Train train = new Train("G123", "Beijing");

        cache.safePut("train:G123", train);

        InOrder inOrder = inOrder(valueOperations, bloomFilter);
        inOrder.verify(valueOperations).set("train:G123", JSON.toJSONString(train));
        inOrder.verify(bloomFilter).add("train:G123");
    }

    @Test
    void shouldLoadAndCacheValueOnlyWhenMissing() {
        Train train = new Train("G123", "Beijing");
        Supplier<Train> loader = org.mockito.Mockito.mock(Supplier.class);
        when(valueOperations.get("train:G123")).thenReturn(null);
        when(loader.get()).thenReturn(train);

        assertThat(cache.getOrLoad("train:G123", Train.class, loader, TTL)).isEqualTo(train);
        verify(loader).get();
        verify(valueOperations).set("train:G123", JSON.toJSONString(train), TTL);
    }

    @Test
    void shouldSkipLoaderWhenCacheIsHit() {
        Train train = new Train("G123", "Beijing");
        Supplier<Train> loader = org.mockito.Mockito.mock(Supplier.class);
        when(valueOperations.get("train:G123")).thenReturn(JSON.toJSONString(train));

        assertThat(cache.getOrLoad("train:G123", Train.class, loader, TTL)).isEqualTo(train);
        verify(loader, never()).get();
    }

    @Test
    void shouldRejectSafeReadWhenBloomFilterSaysKeyDoesNotExist() {
        Supplier<Train> loader = org.mockito.Mockito.mock(Supplier.class);
        when(valueOperations.get("train:G404")).thenReturn(null);
        when(bloomFilter.contains("train:G404")).thenReturn(false);

        assertThat(cache.safeGet("train:G404", Train.class, loader, TTL)).isNull();
        verify(loader, never()).get();
        verify(redissonClient, never()).getLock(anyString());
    }

    @Test
    void shouldCheckCacheAgainAfterAcquiringLock() {
        Train train = new Train("G123", "Beijing");
        Supplier<Train> loader = org.mockito.Mockito.mock(Supplier.class);
        when(valueOperations.get("train:G123"))
                .thenReturn(null, JSON.toJSONString(train));
        when(bloomFilter.contains("train:G123")).thenReturn(true);
        when(redissonClient.getLock("rag:cache:lock:train:G123")).thenReturn(lock);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        assertThat(cache.safeGet("train:G123", Train.class, loader, TTL)).isEqualTo(train);
        verify(lock).lock();
        verify(loader, never()).get();
        verify(lock).unlock();
    }

    @Test
    void shouldLoadAndCacheValueInsideLockWhenStillMissing() {
        Train train = new Train("G123", "Beijing");
        Supplier<Train> loader = org.mockito.Mockito.mock(Supplier.class);
        when(valueOperations.get("train:G123")).thenReturn(null);
        when(bloomFilter.contains("train:G123")).thenReturn(true);
        when(redissonClient.getLock("rag:cache:lock:train:G123")).thenReturn(lock);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(loader.get()).thenReturn(train);

        assertThat(cache.safeGet("train:G123", Train.class, loader, TTL)).isEqualTo(train);
        verify(lock).lock();
        verify(loader).get();
        verify(valueOperations).set("train:G123", JSON.toJSONString(train), TTL);
        verify(lock).unlock();
    }

    @Test
    void shouldWriteCacheBeforeAddingBloomFilter() {
        Train train = new Train("G123", "Beijing");

        cache.safePut("train:G123", train, TTL);

        InOrder inOrder = inOrder(valueOperations, bloomFilter);
        inOrder.verify(valueOperations).set("train:G123", JSON.toJSONString(train), TTL);
        inOrder.verify(bloomFilter).add("train:G123");
    }

    @Test
    void shouldPutAllKeysAtomicallyAndCountExistingKeys() {
        Map<String, Object> entries = new LinkedHashMap<>();
        entries.put("seat:{G123}:1", "available");
        entries.put("seat:{G123}:2", "available");
        List<String> keys = List.copyOf(entries.keySet());
        when(redisTemplate.execute(any(RedisScript.class), eq(keys), any(), any(), any()))
                .thenReturn(1L);
        when(redisTemplate.countExistingKeys(keys)).thenReturn(2L);

        assertThat(cache.putIfAllAbsent(entries, TTL)).isTrue();
        assertThat(cache.countExistingKeys(keys)).isEqualTo(2L);
        verify(redisTemplate).execute(
                any(RedisScript.class),
                eq(keys),
                eq(JSON.toJSONString("available")),
                eq(JSON.toJSONString("available")),
                eq(Long.toString(TTL.toMillis()))
        );
    }

    @Test
    void shouldRejectSubMillisecondTtlBeforeExecutingAtomicWrite() {
        Map<String, Object> entries = Map.of("seat:{G123}:1", "available");

        assertThatThrownBy(() -> cache.putIfAllAbsent(entries, Duration.ofNanos(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ttl")
                .hasMessageContaining("at least 1 millisecond");

        verifyNoInteractions(redisTemplate);
    }

    @Test
    void shouldRejectSubMillisecondTtlForPlainCacheBeforeAccessingRedis() {
        Train train = new Train("G123", "Beijing");
        Supplier<Train> loader = org.mockito.Mockito.mock(Supplier.class);

        assertThatThrownBy(() -> cache.put("train:G123", train, Duration.ofNanos(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 1 millisecond");
        assertThatThrownBy(() -> cache.getOrLoad("train:G123", Train.class, loader, Duration.ofNanos(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 1 millisecond");

        verifyNoInteractions(redisTemplate, valueOperations, loader);
    }

    @Test
    void shouldRejectTtlMillisecondOverflowWithClearArgumentError() {
        Duration overflowingTtl = Duration.ofSeconds(Long.MAX_VALUE);
        Train train = new Train("G123", "Beijing");
        Supplier<Train> loader = org.mockito.Mockito.mock(Supplier.class);
        Map<String, Object> entries = Map.of("seat:{G123}:1", "available");

        assertThatThrownBy(() -> cache.putIfAllAbsent(entries, overflowingTtl))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ttl")
                .hasMessageContaining("milliseconds")
                .hasCauseInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> cache.put("train:G123", train, overflowingTtl))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ttl")
                .hasMessageContaining("milliseconds")
                .hasCauseInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> cache.getOrLoad("train:G123", Train.class, loader, overflowingTtl))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ttl")
                .hasMessageContaining("milliseconds")
                .hasCauseInstanceOf(ArithmeticException.class);

        verifyNoInteractions(redisTemplate, valueOperations, loader);
    }

    @Test
    void shouldAcceptOneMillisecondTtlForAtomicAndPlainWrites() {
        Duration minimumTtl = Duration.ofMillis(1);
        Train train = new Train("G123", "Beijing");
        Map<String, Object> entries = Map.of("seat:{G123}:1", "available");
        List<String> keys = List.copyOf(entries.keySet());
        when(redisTemplate.execute(any(RedisScript.class), eq(keys), any(), any())).thenReturn(1L);

        assertThat(cache.putIfAllAbsent(entries, minimumTtl)).isTrue();
        cache.put("train:G123", train, minimumTtl);
        assertThat(cache.getOrLoad("train:G124", Train.class, () -> train, minimumTtl)).isEqualTo(train);

        verify(redisTemplate).execute(
                any(RedisScript.class), eq(keys), eq(JSON.toJSONString("available")), eq("1")
        );
        verify(valueOperations).set("train:G123", JSON.toJSONString(train), minimumTtl);
        verify(valueOperations).set("train:G124", JSON.toJSONString(train), minimumTtl);
    }

    @Test
    void shouldRejectAtomicWriteAcrossDifferentHashTags() {
        Map<String, Object> entries = Map.of(
                "seat:{G123}:1", "available",
                "seat:{G124}:1", "available"
        );

        assertThatThrownBy(() -> cache.putIfAllAbsent(entries, TTL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("hash tag");
    }

    @Test
    void shouldExposeUnderlyingClients() {
        assertThat(cache.getRedisTemplate()).isSameAs(redisTemplate);
        assertThat(cache.getRedissonClient()).isSameAs(redissonClient);
    }

    @Test
    void shouldRejectInvalidCacheArgumentsBeforeAccessingRedis() {
        assertThatThrownBy(() -> cache.get("train:G123", null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> cache.put("train:G123", null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> cache.getOrLoad(
                "train:G123",
                Train.class,
                () -> null,
                Duration.ZERO
        )).isInstanceOf(IllegalArgumentException.class);
    }

    private record Train(String number, String departure) {
    }
}
