package com.lzj.rag.framework.common.toolkit;

import com.lzj.rag.framework.convention.exception.ClientException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AssertTest {

    @Test
    void shouldReturnValidatedValues() {
        assertThat(Assert.notNull(123, "值不能为空")).isEqualTo(123);
        assertThat(Assert.notBlank("G1", "车次不能为空")).isEqualTo("G1");
        Assert.isTrue(2 > 1, "条件不成立");
    }

    @Test
    void shouldThrowClientExceptionWhenValidationFails() {
        assertThatThrownBy(() -> Assert.isTrue(false, "条件不成立"))
                .isInstanceOf(ClientException.class)
                .hasMessage("条件不成立");
        assertThatThrownBy(() -> Assert.notNull(null, "对象不能为空"))
                .isInstanceOf(ClientException.class)
                .hasMessage("对象不能为空");
        assertThatThrownBy(() -> Assert.notBlank("  ", "用户名不能为空"))
                .isInstanceOf(ClientException.class)
                .hasMessage("用户名不能为空");
    }
}
