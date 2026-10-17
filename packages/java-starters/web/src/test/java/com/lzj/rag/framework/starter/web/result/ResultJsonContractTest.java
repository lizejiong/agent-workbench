package com.lzj.rag.framework.starter.web.result;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ResultJsonContractTest {

    @Test
    void shouldExposeOnlyTheSharedResponseFields() {
        JsonNode json = new ObjectMapper().valueToTree(Results.success("hello", "request-1"));
        Set<String> fields = new HashSet<>();
        json.fieldNames().forEachRemaining(fields::add);

        assertThat(fields).containsExactlyInAnyOrder("code", "message", "data", "requestId");
        assertThat(json.get("code").asText()).isEqualTo("0");
        assertThat(json.get("requestId").asText()).isEqualTo("request-1");
    }
}
