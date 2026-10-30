package com.lzj.rag.framework.starter.web.config;

import com.lzj.rag.framework.starter.web.filter.ReactiveRequestIdFilter;
import com.lzj.rag.framework.starter.web.filter.RequestIdFilter;
import com.lzj.rag.framework.starter.web.handler.GlobalExceptionHandler;
import com.lzj.rag.framework.starter.web.handler.ReactiveGlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;

class ReactiveWebAutoConfigurationTest {
    private final AutoConfigurations configurations = AutoConfigurations.of(
            WebAutoConfiguration.class, ReactiveWebAutoConfiguration.class);

    @Test
    void reactiveApplicationOnlyReceivesReactiveComponents() {
        new ReactiveWebApplicationContextRunner().withConfiguration(configurations).run(context -> {
            assertThat(context).hasSingleBean(ReactiveRequestIdFilter.class);
            assertThat(context).hasSingleBean(ReactiveGlobalExceptionHandler.class);
            assertThat(context).doesNotHaveBean(RequestIdFilter.class);
            assertThat(context).doesNotHaveBean(GlobalExceptionHandler.class);
        });
    }

    @Test
    void mvcApplicationsStillStartWithoutReactiveDependencies() {
        new WebApplicationContextRunner().withConfiguration(configurations)
                .withClassLoader(new FilteredClassLoader("org.springframework.web.reactive", "reactor"))
                .run(context -> {
                    assertThat(context).hasSingleBean(RequestIdFilter.class);
                    assertThat(context).hasSingleBean(GlobalExceptionHandler.class);
                    assertThat(context).doesNotHaveBean("reactiveRequestIdFilter");
                    assertThat(context).doesNotHaveBean("reactiveGlobalExceptionHandler");
                });
    }

    @Test
    void applicationCanReplaceReactiveComponentsAndConfigureErrorContract() {
        var filter = new ReactiveRequestIdFilter();
        var handler = new ReactiveGlobalExceptionHandler(new ReactiveWebProperties());
        new ReactiveWebApplicationContextRunner().withConfiguration(configurations)
                .withPropertyValues("rag.web.reactive.input-code=INVALID_INPUT", "rag.web.reactive.internal-code=INTERNAL")
                .withBean(ReactiveRequestIdFilter.class, () -> filter)
                .withBean(ReactiveGlobalExceptionHandler.class, () -> handler)
                .run(context -> {
                    assertThat(context.getBean(ReactiveRequestIdFilter.class)).isSameAs(filter);
                    assertThat(context.getBean(ReactiveGlobalExceptionHandler.class)).isSameAs(handler);
                    var properties = context.getBean(ReactiveWebProperties.class);
                    assertThat(properties.getInputCode()).isEqualTo("INVALID_INPUT");
                    assertThat(properties.getInternalCode()).isEqualTo("INTERNAL");
                });
    }

    @Test
    void nonWebApplicationReceivesNeitherAdapter() {
        new ApplicationContextRunner().withConfiguration(configurations).run(context -> {
            assertThat(context).doesNotHaveBean(ReactiveRequestIdFilter.class);
            assertThat(context).doesNotHaveBean(ReactiveGlobalExceptionHandler.class);
            assertThat(context).doesNotHaveBean(RequestIdFilter.class);
        });
    }
}
