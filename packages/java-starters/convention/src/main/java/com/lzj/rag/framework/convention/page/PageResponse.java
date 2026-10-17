package com.lzj.rag.framework.convention.page;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Framework-neutral pagination response.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PageResponse<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Builder.Default
    private long current = 1L;

    @Builder.Default
    private long size = 10L;

    private long total;

    @Builder.Default
    private List<T> records = Collections.emptyList();

    /**
     * Maps records to another representation while preserving page metadata.
     */
    public <R> PageResponse<R> convert(Function<? super T, R> mapper) {
        Objects.requireNonNull(mapper, "mapper must not be null");
        List<T> sourceRecords = records == null ? Collections.emptyList() : records;
        List<R> convertedRecords = sourceRecords.stream().map(mapper).toList();
        return new PageResponse<>(current, size, total, convertedRecords);
    }
}
