package com.lzj.rag.backend.refresh.controller.request;

import jakarta.validation.constraints.*;

/**
 * 刷新计划配置，Cron 表达式必须配套明确时区。
 *
 * @param version 当前记录版本或本次请求固定的版本标记。
 * @param enabled 是否启用该资源或能力。
 * @param cron 刷新计划的 Cron 表达式。
 * @param timezone 解释刷新 Cron 表达式所使用的明确时区。
 */
public record RefreshScheduleRequest(
        @NotNull @Positive Long version,
        @NotNull Boolean enabled,
        @NotBlank @Size(max = 128) String cron,
        @NotBlank @Size(max = 64) String timezone) {}
