package com.lzj.rag.framework.starter.log.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 方法调用日志配置。
 */
@ConfigurationProperties(prefix = "rag.log")
public class LogProperties {

    private boolean enabled = true;

    private int maxContentLength = 4096;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxContentLength() {
        return maxContentLength;
    }

    public void setMaxContentLength(int maxContentLength) {
        if (maxContentLength <= 0) {
            throw new IllegalArgumentException("maxContentLength must be greater than zero");
        }
        this.maxContentLength = maxContentLength;
    }
}
