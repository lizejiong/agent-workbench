package com.lzj.rag.framework.starter.web.handler;

import com.lzj.rag.framework.convention.errorcode.BaseErrorCode;
import com.lzj.rag.framework.convention.exception.ClientException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new FailureController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void shouldReturnCustomExceptionCodeAndMessage() throws Exception {
        mockMvc.perform(get("/failure/custom"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BaseErrorCode.CLIENT_ERROR.code()))
                .andExpect(jsonPath("$.message").value("invalid passenger"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void shouldHideUnexpectedExceptionDetails() throws Exception {
        mockMvc.perform(get("/failure/unexpected"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BaseErrorCode.SERVICE_ERROR.code()))
                .andExpect(jsonPath("$.message").value(BaseErrorCode.SERVICE_ERROR.message()))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void shouldMapUnreadableRequestBodyAsClientError() throws Exception {
        mockMvc.perform(post("/failure/body")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{invalid-json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BaseErrorCode.CLIENT_ERROR.code()))
                .andExpect(jsonPath("$.message").value(BaseErrorCode.CLIENT_ERROR.message()))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @RestController
    static class FailureController {

        @GetMapping("/failure/custom")
        void custom() {
            throw new ClientException("invalid passenger");
        }

        @GetMapping("/failure/unexpected")
        void unexpected() {
            throw new IllegalStateException("internal secret");
        }

        @PostMapping("/failure/body")
        Map<String, String> body(@RequestBody Map<String, String> body) {
            return body;
        }
    }
}
