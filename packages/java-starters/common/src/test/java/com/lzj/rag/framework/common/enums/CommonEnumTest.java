package com.lzj.rag.framework.common.enums;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CommonEnumTest {

    @Test
    void shouldExposeStableCommonCodes() {
        assertThat(DeleteEnum.NORMAL.code()).isZero();
        assertThat(DeleteEnum.DELETED.code()).isEqualTo(1);
        assertThat(FlagEnum.NO.code()).isZero();
        assertThat(FlagEnum.YES.code()).isEqualTo(1);
        assertThat(OperationTypeEnum.CREATE.code()).isEqualTo(1);
        assertThat(OperationTypeEnum.UPDATE.code()).isEqualTo(2);
        assertThat(OperationTypeEnum.DELETE.code()).isEqualTo(3);
        assertThat(OperationTypeEnum.QUERY.code()).isEqualTo(4);
        assertThat(StatusEnum.DISABLED.code()).isZero();
        assertThat(StatusEnum.ENABLED.code()).isEqualTo(1);
    }
}
