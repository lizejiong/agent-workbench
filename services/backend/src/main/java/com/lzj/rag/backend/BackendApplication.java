package com.lzj.rag.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 业务后端启动入口，装配知识库、文档入库和检索相关组件。 */
@SpringBootApplication
public class BackendApplication {

    /**
     * 启动应用并读取入口参数。
     *
     * @param args 命令行入口参数。
     */
    public static void main(String[] args) {
        SpringApplication.run(BackendApplication.class, args);
    }
}
