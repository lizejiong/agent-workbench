package com.lzj.rag.framework.common.toolkit;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ThreadFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ThreadUtilTest {

    @Test
    void shouldCreateSequentiallyNamedThreads() {
        ThreadFactory factory = ThreadUtil.newThreadFactory("ticket-worker");

        assertThat(factory.newThread(() -> {}).getName()).isEqualTo("ticket-worker-1");
        assertThat(factory.newThread(() -> {}).getName()).isEqualTo("ticket-worker-2");
    }

    @Test
    void shouldRestoreInterruptedStateWhenSleepIsInterrupted() {
        Thread.currentThread().interrupt();

        ThreadUtil.sleep(1L);

        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        Thread.interrupted();
    }

    @Test
    void shouldRejectBlankPrefixAndNegativeDuration() {
        assertThatThrownBy(() -> ThreadUtil.newThreadFactory(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("thread name prefix must not be blank");
        assertThatThrownBy(() -> ThreadUtil.sleep(-1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("millis must not be negative");
    }
}
