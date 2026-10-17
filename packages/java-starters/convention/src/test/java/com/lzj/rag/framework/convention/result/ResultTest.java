package com.lzj.rag.framework.convention.result;

import com.lzj.rag.framework.convention.errorcode.BaseErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ResultTest {

    @Test
    void shouldCreateSuccessfulResultWithData() {
        Result<String> result = Result.success("G123");

        assertThat(Result.SUCCESS_CODE).isEqualTo("0");
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getCode()).isEqualTo(Result.SUCCESS_CODE);
        assertThat(result.getMessage()).isEqualTo(Result.SUCCESS_MESSAGE);
        assertThat(result.getData()).isEqualTo("G123");
    }

    @Test
    void shouldCreateSuccessfulResultWithoutData() {
        Result<Void> result = Result.success();

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).isNull();
    }

    @Test
    void shouldCreateFailureResultAndCarryRequestId() {
        Result<Void> result = Result.<Void>failure(BaseErrorCode.CLIENT_ERROR)
                .setRequestId("request-123");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getCode()).isEqualTo("A000001");
        assertThat(result.getMessage()).isEqualTo("客户端请求错误");
        assertThat(result.getRequestId()).isEqualTo("request-123");
    }
}
