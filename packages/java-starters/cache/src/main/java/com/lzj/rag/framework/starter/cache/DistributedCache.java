package com.lzj.rag.framework.starter.cache;

import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 分布式缓存统一操作接口。
 */
public interface DistributedCache {

    /**
     * 读取缓存，并按指定类型反序列化；Key 不存在时返回 {@code null}。
     */
    <T> T get(String key, Class<T> type);

    /**
     * 写入永久缓存。
     */
    void put(String key, Object value);

    /**
     * 写入带有效期的缓存。
     */
    void put(String key, Object value, Duration ttl);

    /**
     * 删除单个缓存 Key。
     */
    boolean delete(String key);

    /**
     * 批量删除缓存 Key，返回实际删除数量。
     */
    long delete(Collection<String> keys);

    /**
     * 判断缓存 Key 是否存在。
     */
    boolean hasKey(String key);

    /**
     * 缓存未命中时调用加载器，并将非空结果写回缓存。
     * <p>
     * 本方法不提供并发保护，热点 Key 应使用 {@link #safeGet}。
     */
    <T> T getOrLoad(String key, Class<T> type, Supplier<T> loader, Duration ttl);

    /**
     * 使用布隆过滤器防穿透，并通过分布式锁和锁后二次检查防止缓存击穿。
     * <p>
     * 调用前必须确保合法 Key 已预热到布隆过滤器，新数据应通过 {@link #safePut} 写入。
     * 应用须提供 RedissonClient 和布隆过滤器，缺少任一组件时抛出配置异常。
     */
    <T> T safeGet(String key, Class<T> type, Supplier<T> loader, Duration ttl);

    /**
     * 先写入永久缓存，再将 Key 加入布隆过滤器。
     * 未配置布隆过滤器时，在写入缓存前抛出配置异常。
     */
    void safePut(String key, Object value);

    /**
     * 先写入带有效期的缓存，再将 Key 加入布隆过滤器。
     * 未配置布隆过滤器时，在写入缓存前抛出配置异常。
     */
    void safePut(String key, Object value, Duration ttl);

    /**
     * 仅当全部 Key 都不存在时原子写入所有永久缓存。
     * <p>
     * 所有 Key 必须包含相同的 Redis Cluster hash tag。
     */
    boolean putIfAllAbsent(Map<String, ?> entries);

    /**
     * 仅当全部 Key 都不存在时原子写入所有缓存，并设置统一有效期。
     * <p>
     * 所有 Key 必须包含相同的 Redis Cluster hash tag。
     */
    boolean putIfAllAbsent(Map<String, ?> entries, Duration ttl);

    /**
     * 统计给定 Key 中当前存在的数量。
     */
    long countExistingKeys(Collection<String> keys);

    /**
     * 获取底层 Spring Data Redis 客户端，用于未被本接口覆盖的操作。
     */
    StringRedisTemplate getRedisTemplate();

    /**
     * 获取底层 Redisson 客户端，用于未被本接口覆盖的分布式能力。
     * 应用未提供客户端时返回 {@code null}。
     */
    RedissonClient getRedissonClient();
}
