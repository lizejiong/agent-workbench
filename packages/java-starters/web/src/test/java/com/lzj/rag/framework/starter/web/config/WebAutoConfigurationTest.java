package com.lzj.rag.framework.starter.web.config;

import com.lzj.rag.framework.starter.web.handler.GlobalExceptionHandler;
import com.lzj.rag.framework.starter.web.filter.RequestIdFilter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class WebAutoConfigurationTest {

    private final WebApplicationContextRunner webContextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(WebAutoConfiguration.class));

    @Test
    void shouldConfigureExceptionHandlerByDefault() {
        webContextRunner.run(context -> {
            assertThat(context).hasSingleBean(GlobalExceptionHandler.class);
            assertThat(context).hasSingleBean(RequestIdFilter.class);
        });
    }

    @Test
    void shouldBackOffForApplicationExceptionHandler() {
        GlobalExceptionHandler customHandler = new GlobalExceptionHandler();

        webContextRunner
                .withBean(GlobalExceptionHandler.class, () -> customHandler)
                .run(context -> assertThat(context.getBean(GlobalExceptionHandler.class))
                        .isSameAs(customHandler));
    }

    @Test
    void shouldNotConfigureInNonWebApplication() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(WebAutoConfiguration.class))
                .run(context ->
                        assertThat(context).doesNotHaveBean(GlobalExceptionHandler.class));
    }

    @Test
    void shouldBackOffForApplicationRequestIdFilter() {
        RequestIdFilter customFilter = new RequestIdFilter();

        webContextRunner.withBean(RequestIdFilter.class, () -> customFilter)
                .run(context -> assertThat(context.getBean(RequestIdFilter.class)).isSameAs(customFilter));
    }
}
