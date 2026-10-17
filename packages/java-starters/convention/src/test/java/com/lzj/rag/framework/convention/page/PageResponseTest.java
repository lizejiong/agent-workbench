package com.lzj.rag.framework.convention.page;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PageResponseTest {

    @Test
    void shouldProvideStablePaginationDefaults() {
        PageRequest request = new PageRequest();
        PageResponse<Object> response = new PageResponse<>();

        assertThat(request.getCurrent()).isEqualTo(1L);
        assertThat(request.getSize()).isEqualTo(10L);
        assertThat(response.getCurrent()).isEqualTo(1L);
        assertThat(response.getSize()).isEqualTo(10L);
        assertThat(response.getTotal()).isZero();
        assertThat(response.getRecords()).isEmpty();
    }

    @Test
    void shouldConvertRecordsWithoutMutatingSourcePage() {
        PageResponse<String> source = new PageResponse<>(
                2L,
                20L,
                2L,
                List.of("G1", "G123")
        );

        PageResponse<Integer> converted = source.convert(String::length);

        assertThat(converted).isNotSameAs(source);
        assertThat(converted.getCurrent()).isEqualTo(2L);
        assertThat(converted.getSize()).isEqualTo(20L);
        assertThat(converted.getTotal()).isEqualTo(2L);
        assertThat(converted.getRecords()).containsExactly(2, 4);
        assertThat(source.getRecords()).containsExactly("G1", "G123");
    }

    @Test
    void shouldTreatNullRecordsAsEmptyDuringConversion() {
        PageResponse<String> source = new PageResponse<>(1L, 10L, 0L, null);

        PageResponse<Integer> converted = source.convert(String::length);

        assertThat(converted.getRecords()).isEmpty();
    }
}
