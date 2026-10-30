package com.lzj.rag.ai;

import com.lzj.rag.ai.model.config.AiProperties;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/** AI 服务启动入口，组件扫描从本包向下覆盖模型和文本业务。 */
@SpringBootApplication
@EnableConfigurationProperties(AiProperties.class)
public class AiApplication {

    /**
     * 启动 Spring Boot 应用，并读取命令行与外部配置。
     *
     * @param args 传给 Spring Boot 的命令行参数。
     */
    public static void main(String[] args) {
        SpringApplication.run(AiApplication.class, args);
    }
}
