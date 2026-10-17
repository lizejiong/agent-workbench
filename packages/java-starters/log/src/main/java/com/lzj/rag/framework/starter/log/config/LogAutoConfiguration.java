package com.lzj.rag.framework.starter.log.config;

import com.lzj.rag.framework.starter.log.aspect.ILogAspect;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 方法调用日志自动配置。
 */
@AutoConfiguration
@ConditionalOnClass(Aspect.class)
@ConditionalOnProperty(prefix = "rag.log", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(LogProperties.class)
public class LogAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(ILogAspect.class)
    public ILogAspect iLogAspect(LogProperties properties) {
        return new ILogAspect(properties);
    }
}
