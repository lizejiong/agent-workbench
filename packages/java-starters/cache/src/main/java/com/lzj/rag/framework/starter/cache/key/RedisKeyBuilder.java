package com.lzj.rag.framework.starter.cache.key;

import java.util.ArrayList;
import java.util.List;

/**
 * 按“应用前缀:业务命名空间:业务标识”构造 Redis Key。
 */
public final class RedisKeyBuilder {

    private static final String DELIMITER = ":";

    private final String keyPrefix;

    public RedisKeyBuilder(String keyPrefix) {
        this.keyPrefix = requireSegment(keyPrefix, "key prefix");
    }

    /**
     * 构造普通业务 Key，例如 {@code rag:train:G123}。
     */
    public String build(String namespace, Object... identifiers) {
        return join(namespace, null, identifiers);
    }

    /**
     * 构造带 Redis Cluster hash tag 的 Key，同一 hash tag 的 Key 会进入同一槽位。
     */
    public String buildWithHashTag(String namespace, String hashTag, Object... identifiers) {
        String resolvedHashTag = requireSegment(hashTag, "hash tag");
        if (resolvedHashTag.indexOf('{') >= 0 || resolvedHashTag.indexOf('}') >= 0) {
            throw new IllegalArgumentException("hash tag must not contain braces");
        }
        return join(namespace, "{" + resolvedHashTag + "}", identifiers);
    }

    private String join(String namespace, String hashTag, Object[] identifiers) {
        List<String> segments = new ArrayList<>();
        segments.add(keyPrefix);
        segments.add(requireSegment(namespace, "namespace"));
        if (hashTag != null) {
            segments.add(hashTag);
        }
        if (identifiers != null) {
            for (Object identifier : identifiers) {
                segments.add(requireSegment(identifier, "identifier"));
            }
        }
        return String.join(DELIMITER, segments);
    }

    private static String requireSegment(Object value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        String segment = String.valueOf(value).trim();
        if (segment.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return segment;
    }
}
