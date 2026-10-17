package com.lzj.rag.framework.starter.web.filter;

import com.lzj.rag.framework.starter.web.context.RequestIdContext;
import com.lzj.rag.framework.starter.web.result.Results;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @AfterEach
    void clearContext() {
        RequestIdContext.clear();
    }

    @Test
    void shouldReuseIncomingIdInResponsesAndClearContextAfterwards() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.HEADER_NAME, "upstream-request-1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            assertThat(RequestIdContext.get()).isEqualTo("upstream-request-1");
            assertThat(Results.success().getRequestId()).isEqualTo("upstream-request-1");
            assertThat(Results.failure("B000001", "failed").getRequestId())
                    .isEqualTo("upstream-request-1");
            assertThat(Results.success(null, "explicit-id").getRequestId()).isEqualTo("explicit-id");
        });

        assertThat(response.getHeader(RequestIdFilter.HEADER_NAME)).isEqualTo("upstream-request-1");
        assertThat(RequestIdContext.get()).isNull();
    }

    @Test
    void shouldGenerateAnIdWhenThereIsNoIncomingHeader() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest(), response, (req, res) ->
                assertThat(Results.success().getRequestId())
                        .isEqualTo(response.getHeader(RequestIdFilter.HEADER_NAME))
                        .hasSize(32));

        assertThat(RequestIdContext.get()).isNull();
    }

    @Test
    void shouldClearContextEvenWhenTheRequestFails() {
        ServletException failure = new ServletException("failed");

        assertThatThrownBy(() -> filter.doFilter(
                new MockHttpServletRequest(), new MockHttpServletResponse(),
                (req, res) -> { throw failure; }))
                .isSameAs(failure);

        assertThat(RequestIdContext.get()).isNull();
    }
}
