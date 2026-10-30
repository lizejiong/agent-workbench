package com.lzj.rag.framework.starter.web.config;

import com.lzj.rag.framework.starter.web.filter.ReactiveRequestIdFilter;
import com.lzj.rag.framework.starter.web.handler.ReactiveGlobalExceptionHandler;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@ConditionalOnClass(name = "org.springframework.web.reactive.DispatcherHandler")
@EnableConfigurationProperties(ReactiveWebProperties.class)
public class ReactiveWebAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(ReactiveRequestIdFilter.class)
    public ReactiveRequestIdFilter reactiveRequestIdFilter() { return new ReactiveRequestIdFilter(); }

    @Bean
    @ConditionalOnMissingBean(ReactiveGlobalExceptionHandler.class)
    public ReactiveGlobalExceptionHandler reactiveGlobalExceptionHandler(ReactiveWebProperties properties) {
        return new ReactiveGlobalExceptionHandler(properties);
    }
}
