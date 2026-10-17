package com.lzj.rag.framework.common.toolkit;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EnvironmentUtilTest {

    @Test
    void shouldRecognizeActiveProfiles() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev", "integration");

        assertThat(EnvironmentUtil.isDevelopment(environment)).isTrue();
        assertThat(EnvironmentUtil.isTest(environment)).isFalse();
        assertThat(EnvironmentUtil.isProduction(environment)).isFalse();
        assertThat(EnvironmentUtil.isActiveProfile(environment, "integration")).isTrue();
    }

    @Test
    void shouldRejectNullEnvironmentAndBlankProfile() {
        assertThatThrownBy(() -> EnvironmentUtil.isDevelopment(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("environment must not be null");
        assertThatThrownBy(() -> EnvironmentUtil.isActiveProfile(new MockEnvironment(), " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("profile must not be blank");
    }
}
