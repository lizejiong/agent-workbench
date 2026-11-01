package com.lzj.rag.backend.rag.config;

import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("kb.rag.execution")
public record AnswerExecutionProperties(@DefaultValue("4") @Min(1) @Max(32) int concurrency) {}
