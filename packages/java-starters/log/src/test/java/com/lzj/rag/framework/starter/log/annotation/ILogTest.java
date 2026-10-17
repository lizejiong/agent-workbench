package com.lzj.rag.framework.starter.log.annotation;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class ILogTest {

    @Test
    void shouldUseSafeDefaults() throws NoSuchMethodException {
        Method method = SampleService.class.getDeclaredMethod("query");

        ILog annotation = method.getAnnotation(ILog.class);

        assertThat(annotation.value()).isEmpty();
        assertThat(annotation.recordArgs()).isFalse();
        assertThat(annotation.recordResult()).isFalse();
    }

    static class SampleService {

        @ILog
        void query() {
        }
    }
}
