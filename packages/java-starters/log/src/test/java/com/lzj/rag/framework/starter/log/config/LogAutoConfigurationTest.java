package com.lzj.rag.framework.starter.log.config;

import com.lzj.rag.framework.starter.log.annotation.ILog;
import com.lzj.rag.framework.starter.log.aspect.ILogAspect;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class LogAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    AopAutoConfiguration.class,
                    LogAutoConfiguration.class))
            .withUserConfiguration(TestConfiguration.class);

    @Test
    void shouldConfigureAspectAndProxyLoggedBeanByDefault() {
        contextRunner
                .withPropertyValues("rag.log.max-content-length=128")
                .run(context -> {
                    assertThat(context).hasSingleBean(ILogAspect.class);
                    assertThat(context).hasSingleBean(LogProperties.class);
                    assertThat(context.getBean(LogProperties.class).getMaxContentLength()).isEqualTo(128);
                    assertThat(AopUtils.isAopProxy(context.getBean(LoggedService.class))).isTrue();
                    assertThat(context.getBean(LoggedService.class).query()).isEqualTo("G1");
                });
    }

    @Test
    void shouldNotConfigureAspectWhenDisabled() {
        contextRunner
                .withPropertyValues("rag.log.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(ILogAspect.class);
                    assertThat(AopUtils.isAopProxy(context.getBean(LoggedService.class))).isFalse();
                });
    }

    @Test
    void shouldBackOffWhenApplicationProvidesAspect() {
        LogProperties properties = new LogProperties();
        ILogAspect customAspect = new ILogAspect(properties);

        contextRunner
                .withBean(ILogAspect.class, () -> customAspect)
                .run(context -> assertThat(context.getBean(ILogAspect.class)).isSameAs(customAspect));
    }

    @Configuration(proxyBeanMethods = false)
    static class TestConfiguration {

        @Bean
        LoggedService loggedService() {
            return new LoggedService();
        }
    }

    static class LoggedService {

        @ILog("测试查询")
        public String query() {
            return "G1";
        }
    }
}
