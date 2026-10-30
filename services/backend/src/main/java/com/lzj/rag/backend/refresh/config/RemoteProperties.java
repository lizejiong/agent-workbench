package com.lzj.rag.backend.refresh.config;

import jakarta.validation.constraints.*;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

/**
 * 远程导入的网络许可、下载容量、并发、租约和重试配置。
 *
 * @param enabled 是否启用该资源或能力。
 * @param allowedHosts 允许访问的精确远程主机名称集合。
 * @param trustedPrivateHosts 显式允许连接内网地址的精确主机集合。
 * @param allowedPorts 允许访问的远程端口集合。
 * @param allowHttp 是否显式允许未加密的 HTTP 源地址。
 * @param connectTimeout 建立外部连接的时间预算。
 * @param readTimeout 读取响应数据的等待上限。
 * @param downloadTimeout 单次远程下载的总时间预算。
 * @param maxRedirects 允许跟随的重定向次数上限。
 * @param maxDownloadBytes 单次远程下载允许接收的字节数上限。
 * @param concurrency 允许同时执行的工作任务数量。
 * @param lease 单次任务领取的有效租约时长。
 * @param runDeadline 一次刷新运行可使用的总时间预算。
 * @param maxAttempts 允许的最大执行尝试次数。
 */
@Validated
@ConfigurationProperties("kb.remote")
public record RemoteProperties(
        @DefaultValue("false") boolean enabled,
        List<String> allowedHosts,
        List<String> trustedPrivateHosts,
        @DefaultValue("443,80") List<Integer> allowedPorts,
        @DefaultValue("false") boolean allowHttp,
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("10s") Duration readTimeout,
        @DefaultValue("30s") Duration downloadTimeout,
        @DefaultValue("3") @Min(0) @Max(8) int maxRedirects,
        @DefaultValue("20971520") @Min(1) @Max(1073741824) long maxDownloadBytes,
        @DefaultValue("2") @Min(1) @Max(8) int concurrency,
        @DefaultValue("45s") Duration lease,
        @DefaultValue("15m") Duration runDeadline,
        @DefaultValue("3") @Min(1) @Max(5) int maxAttempts) {

    /** 校验并补齐缺省配置，构造可直接使用的配置快照。 */
    public RemoteProperties {
        allowedHosts = allowedHosts == null ? List.of() : List.copyOf(allowedHosts);
        trustedPrivateHosts =
                trustedPrivateHosts == null ? List.of() : List.copyOf(trustedPrivateHosts);
        if (allowedPorts == null) allowedPorts = List.of(443, 80);
        else allowedPorts = List.copyOf(allowedPorts);
        // duration：当前计算得到的时间间隔。
        for (var duration :
                List.of(connectTimeout, readTimeout, downloadTimeout, lease, runDeadline))
            if (duration == null || duration.isNegative() || duration.isZero())
                throw new IllegalArgumentException("Remote timeouts must be positive");
        if (downloadTimeout.compareTo(Duration.ofMinutes(2)) > 0
                || lease.compareTo(Duration.ofSeconds(15)) < 0
                || runDeadline.compareTo(Duration.ofMinutes(30)) > 0)
            throw new IllegalArgumentException(
                    "Remote download <=2m, lease >=15s and run deadline <=30m required");
        // p：配置中的远程端口号，必须处于有效端口范围。
        if (!allowedHosts.containsAll(trustedPrivateHosts)
                || allowedPorts.isEmpty()
                || allowedPorts.stream().anyMatch(p -> p < 1 || p > 65535))
            throw new IllegalArgumentException(
                    "Private hosts must be explicitly present in allowedHosts; ports must be valid");
        // host：当前连接要求匹配的主机名称。
        for (String host : allowedHosts)
            if (!host.matches("[A-Za-z0-9.:-]{1,253}") || host.contains("*"))
                throw new IllegalArgumentException(
                        "Use exact remote host names, without wildcard or URL");
    }
}
