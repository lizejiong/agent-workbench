package com.lzj.rag.backend.rag.prompt;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 由部署配置选择模板，HTTP 请求和模型输出无权指定文件或版本。 */
@ConfigurationProperties("kb.rag.prompts")
public record PromptProperties(String directory, @DefaultValue("query-understanding-v1") String queryVersion) {}
