package com.lzj.rag.framework.starter.web.result;

import com.lzj.rag.framework.convention.errorcode.BaseErrorCode;
import com.lzj.rag.framework.convention.result.Result;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ResultsTest {

    @Test
    void shouldBuildSuccessResultWithGeneratedRequestId() {
        Result<String> result = Results.success("G1");

        assertThat(result.getCode()).isEqualTo(Result.SUCCESS_CODE);
        assertThat(result.getMessage()).isEqualTo(Result.SUCCESS_MESSAGE);
        assertThat(result.getData()).isEqualTo("G1");
        assertThat(result.getRequestId()).hasSize(32).doesNotContain("-");
    }

    @Test
    void shouldKeepProvidedRequestId() {
        Result<Void> result = Results.success(null, "request-123");

        assertThat(result.getRequestId()).isEqualTo("request-123");
    }

    @Test
    void shouldBuildFailureFromErrorCode() {
        Result<Void> result = Results.failure(BaseErrorCode.CLIENT_ERROR);

        assertThat(result.getCode()).isEqualTo(BaseErrorCode.CLIENT_ERROR.code());
        assertThat(result.getMessage()).isEqualTo(BaseErrorCode.CLIENT_ERROR.message());
        assertThat(result.getRequestId()).hasSize(32);
    }

    @Test
    void shouldBuildFailureFromCodeAndMessage() {
        Result<Void> result = Results.failure("A000100", "车次不存在", "request-456");

        assertThat(result.getCode()).isEqualTo("A000100");
        assertThat(result.getMessage()).isEqualTo("车次不存在");
        assertThat(result.getRequestId()).isEqualTo("request-456");
    }
}
