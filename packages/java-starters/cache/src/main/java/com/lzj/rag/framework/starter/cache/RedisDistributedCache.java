package com.lzj.rag.framework.starter.cache;

import com.alibaba.fastjson2.JSON;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 基于 Spring Data Redis 和 Redisson 的分布式缓存实现。
 */
public final class RedisDistributedCache implements DistributedCache {

    private static final long NO_EXPIRATION = -1L;
    private static final DefaultRedisScript<Long> PUT_IF_ALL_ABSENT_SCRIPT = createPutScript();

    private final StringRedisTemplate redisTemplate;
    private final RedissonClient redissonClient;
    private final RBloomFilter<String> bloomFilter;
    private final String lockKeyPrefix;

    public RedisDistributedCache(
            StringRedisTemplate redisTemplate,
            RedissonClient redissonClient,
            RBloomFilter<String> bloomFilter,
            String lockKeyPrefix) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate must not be null");
        this.redissonClient = redissonClient;
        this.bloomFilter = bloomFilter;
        this.lockKeyPrefix = requireKey(lockKeyPrefix, "lock key prefix");
    }

    @Override
    public <T> T get(String key, Class<T> type) {
        Class<T> resolvedType = Objects.requireNonNull(type, "type must not be null");
        String cachedValue = redisTemplate.opsForValue().get(requireKey(key, "key"));
        if (cachedValue == null) {
            return null;
        }
        return JSON.parseObject(cachedValue, resolvedType);
    }

    @Override
    public void put(String key, Object value) {
        redisTemplate.opsForValue().set(
                requireKey(key, "key"),
                serialize(value)
        );
    }

    @Override
    public void put(String key, Object value, Duration ttl) {
        Duration resolvedTtl = requireTtl(ttl);
        redisTemplate.opsForValue().set(
                requireKey(key, "key"),
                serialize(value),
                resolvedTtl
        );
    }

    @Override
    public boolean delete(String key) {
        return Boolean.TRUE.equals(redisTemplate.delete(requireKey(key, "key")));
    }

    @Override
    public long delete(Collection<String> keys) {
        List<String> resolvedKeys = copyKeys(keys);
        if (resolvedKeys.isEmpty()) {
            return 0L;
        }
        Long deleted = redisTemplate.delete(resolvedKeys);
        return deleted == null ? 0L : deleted;
    }

    @Override
    public boolean hasKey(String key) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(requireKey(key, "key")));
    }

    @Override
    public <T> T getOrLoad(String key, Class<T> type, Supplier<T> loader, Duration ttl) {
        String resolvedKey = requireKey(key, "key");
        Class<T> resolvedType = Objects.requireNonNull(type, "type must not be null");
        Supplier<T> resolvedLoader = requireLoader(loader);
        Duration resolvedTtl = requireTtl(ttl);
        T cachedValue = get(resolvedKey, resolvedType);
        if (cachedValue != null) {
            return cachedValue;
        }
        T loadedValue = resolvedLoader.get();
        if (loadedValue != null) {
            put(resolvedKey, loadedValue, resolvedTtl);
        }
        return loadedValue;
    }

    @Override
    public <T> T safeGet(String key, Class<T> type, Supplier<T> loader, Duration ttl) {
        String resolvedKey = requireKey(key, "key");
        Class<T> resolvedType = Objects.requireNonNull(type, "type must not be null");
        Supplier<T> resolvedLoader = requireLoader(loader);
        Duration resolvedTtl = requireTtl(ttl);
        if (redissonClient == null) {
            throw new IllegalStateException("safeGet requires an application-provided RedissonClient bean");
        }
        RBloomFilter<String> requiredBloomFilter = requireBloomFilter("safeGet");
        T cachedValue = get(resolvedKey, resolvedType);
        if (cachedValue != null) {
            return cachedValue;
        }
        if (!requiredBloomFilter.contains(resolvedKey)) {
            return null;
        }

        // 不指定固定租期，交由 Redisson watchdog 在回源未完成时自动续期。
        RLock lock = redissonClient.getLock(lockKeyPrefix + resolvedKey);
        lock.lock();
        try {
            // 等锁期间其他线程可能已经完成回源，持锁后必须再次读取缓存。
            cachedValue = get(resolvedKey, resolvedType);
            if (cachedValue != null) {
                return cachedValue;
            }
            T loadedValue = resolvedLoader.get();
            if (loadedValue != null) {
                put(resolvedKey, loadedValue, resolvedTtl);
            }
            return loadedValue;
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    public void safePut(String key, Object value) {
        String resolvedKey = requireKey(key, "key");
        RBloomFilter<String> requiredBloomFilter = requireBloomFilter("safePut");
        put(resolvedKey, value);
        requiredBloomFilter.add(resolvedKey);
    }

    @Override
    public void safePut(String key, Object value, Duration ttl) {
        String resolvedKey = requireKey(key, "key");
        RBloomFilter<String> requiredBloomFilter = requireBloomFilter("safePut");
        put(resolvedKey, value, ttl);
        requiredBloomFilter.add(resolvedKey);
    }

    @Override
    public boolean putIfAllAbsent(Map<String, ?> entries) {
        return putIfAllAbsent(entries, NO_EXPIRATION);
    }

    @Override
    public boolean putIfAllAbsent(Map<String, ?> entries, Duration ttl) {
        return putIfAllAbsent(entries, requireTtl(ttl).toMillis());
    }

    @Override
    public long countExistingKeys(Collection<String> keys) {
        List<String> resolvedKeys = copyKeys(keys);
        if (resolvedKeys.isEmpty()) {
            return 0L;
        }
        Long count = redisTemplate.countExistingKeys(resolvedKeys);
        return count == null ? 0L : count;
    }

    @Override
    public StringRedisTemplate getRedisTemplate() {
        return redisTemplate;
    }

    @Override
    public RedissonClient getRedissonClient() {
        return redissonClient;
    }

    private RBloomFilter<String> requireBloomFilter(String operation) {
        if (bloomFilter == null) {
            throw new IllegalStateException(
                    operation + " requires a Bloom filter; set rag.cache.bloom.enabled=true and provide a "
                            + "RedissonClient bean, or provide a bean named ragCacheBloomFilter"
            );
        }
        return bloomFilter;
    }

    private boolean putIfAllAbsent(Map<String, ?> entries, long ttlMillis) {
        if (entries == null || entries.isEmpty()) {
            throw new IllegalArgumentException("entries must not be empty");
        }
        Map<String, Object> resolvedEntries = new LinkedHashMap<>();
        entries.forEach((key, value) -> resolvedEntries.put(
                requireKey(key, "key"),
                Objects.requireNonNull(value, "cache value must not be null")
        ));
        List<String> keys = List.copyOf(resolvedEntries.keySet());
        // Redis Cluster 只允许 Lua 脚本同时操作位于同一槽位的 Key。
        requireSameHashTag(keys);

        List<Object> arguments = new ArrayList<>(keys.size() + 1);
        resolvedEntries.values().stream().map(RedisDistributedCache::serialize).forEach(arguments::add);
        arguments.add(Long.toString(ttlMillis));
        Long result = redisTemplate.execute(
                PUT_IF_ALL_ABSENT_SCRIPT,
                keys,
                arguments.toArray()
        );
        return Long.valueOf(1L).equals(result);
    }

    private static List<String> copyKeys(Collection<String> keys) {
        Objects.requireNonNull(keys, "keys must not be null");
        return keys.stream().map(key -> requireKey(key, "key")).toList();
    }

    private static void requireSameHashTag(List<String> keys) {
        String expectedHashTag = hashTag(keys.get(0));
        boolean sameHashTag = keys.stream()
                .map(RedisDistributedCache::hashTag)
                .allMatch(expectedHashTag::equals);
        if (!sameHashTag) {
            throw new IllegalArgumentException("all keys must use the same Redis Cluster hash tag");
        }
    }

    private static String hashTag(String key) {
        int start = key.indexOf('{');
        int end = start < 0 ? -1 : key.indexOf('}', start + 1);
        if (start < 0 || end <= start + 1) {
            throw new IllegalArgumentException("key must contain a non-empty Redis Cluster hash tag");
        }
        return key.substring(start + 1, end);
    }

    private static String serialize(Object value) {
        return JSON.toJSONString(Objects.requireNonNull(value, "cache value must not be null"));
    }

    private static String requireKey(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    private static Duration requireTtl(Duration ttl) {
        Objects.requireNonNull(ttl, "ttl must not be null");
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        long ttlMillis;
        try {
            ttlMillis = ttl.toMillis();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("ttl is too large to represent in milliseconds", exception);
        }
        if (ttlMillis < 1L) {
            throw new IllegalArgumentException("ttl must be at least 1 millisecond");
        }
        return ttl;
    }

    private static <T> Supplier<T> requireLoader(Supplier<T> loader) {
        return Objects.requireNonNull(loader, "loader must not be null");
    }

    private static DefaultRedisScript<Long> createPutScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setResultType(Long.class);
        // 检查和写入位于同一段 Lua 中，避免并发请求在检查后抢先写入。
        script.setScriptText("""
                for index = 1, #KEYS do
                    if redis.call('exists', KEYS[index]) == 1 then
                        return 0
                    end
                end
                local ttl = tonumber(ARGV[#KEYS + 1])
                for index = 1, #KEYS do
                    if ttl > 0 then
                        redis.call('psetex', KEYS[index], ttl, ARGV[index])
                    else
                        redis.call('set', KEYS[index], ARGV[index])
                    end
                end
                return 1
                """);
        return script;
    }
}
