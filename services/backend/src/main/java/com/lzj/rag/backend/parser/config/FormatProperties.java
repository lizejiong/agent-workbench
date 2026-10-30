package com.lzj.rag.backend.parser.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 本地解析、MinerU 和视觉模型的开关、容量与时间限制。
 *
 * @param localTimeout 本地解析的时间预算。
 * @param concurrency 允许同时执行的工作任务数量。
 * @param processHeapMb 解析子进程的最大堆内存，单位为 MB。
 * @param maxRows 允许解析的表格数据行数上限。
 * @param maxColumns 允许解析的表格列数上限。
 * @param maxSheets 允许解析的 Excel 工作表数量上限。
 * @param maxPages 允许解析的 PDF 页数上限。
 * @param maxImagePixels 允许解析的图片像素总量上限。
 * @param maxImageBytes 允许发送给视觉模型的图片字节数上限。
 * @param maxResultBytes 解析子进程允许写回的结果字节数上限。
 * @param mineru MinerU 解析服务连接与容量配置。
 * @param vlm 图片描述模型配置或视觉客户端。
 */
@Validated
@ConfigurationProperties("kb.formats")
public record FormatProperties(
        @DefaultValue("30s") Duration localTimeout,
        @DefaultValue("2") @Min(1) @Max(8) int concurrency,
        @DefaultValue("256") @Min(128) @Max(1024) int processHeapMb,
        @DefaultValue("20000") @Min(1) @Max(100000) int maxRows,
        @DefaultValue("128") @Min(1) @Max(1024) int maxColumns,
        @DefaultValue("32") @Min(1) @Max(100) int maxSheets,
        @DefaultValue("500") @Min(1) @Max(2000) int maxPages,
        @DefaultValue("16777216") @Min(1024) @Max(50000000) int maxImagePixels,
        @DefaultValue("2097152") @Min(1024) @Max(10000000) int maxImageBytes,
        @DefaultValue("8388608") @Min(65536) @Max(33554432) int maxResultBytes,
        @Valid @DefaultValue Mineru mineru,
        @Valid @DefaultValue Vlm vlm) {

    /**
     * MinerU 服务连接、启用状态和调用限制。
     *
     * @param enabled 是否启用该资源或能力。
     * @param baseUrl 外部服务基础地址。
     * @param token 当前任务的租约栅栏令牌或清理凭据。
     * @param protocol 本次请求或响应使用的公开协议版本。
     * @param timeout 当前阶段允许使用的局部时间预算。
     * @param backend 当前格式使用的解析后端标识。
     * @param language 外部解析器使用的文档语言配置。
     * @param connectTimeout 建立外部连接的时间预算。
     * @param concurrency 允许同时执行的工作任务数量。
     */
    public record Mineru(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("") String baseUrl,
            @DefaultValue("") String token,
            @DefaultValue("mineru-2.7.6-file-parse") String protocol,
            @DefaultValue("90s") Duration timeout,
            @DefaultValue("pipeline") String backend,
            @DefaultValue("ch") String language,
            @DefaultValue("5s") Duration connectTimeout,
            @DefaultValue("1") @Min(1) @Max(8) int concurrency) {}

    /**
     * 图片描述模型选择及生成参数。
     *
     * @param enabled 是否启用该资源或能力。
     * @param model 当前固定的模型配置或 AI 返回的模型身份节点。
     * @param maxOutputTokens 视觉模型允许生成的最大 Token 数。
     */
    public record Vlm(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("qwen-vision") @NotBlank @Pattern(regexp = "[A-Za-z0-9._-]{1,128}")
                    String model,
            @DefaultValue("2048") @Min(64) @Max(8192) int maxOutputTokens) {}

    /** 校验并补齐缺省配置，构造可直接使用的配置快照。 */
    public FormatProperties {
        if (localTimeout != null
                && (localTimeout.isNegative()
                        || localTimeout.isZero()
                        || localTimeout.compareTo(Duration.ofMinutes(2)) > 0))
            throw new IllegalArgumentException("Local parser timeout must be positive and <=2m");
    }
}
