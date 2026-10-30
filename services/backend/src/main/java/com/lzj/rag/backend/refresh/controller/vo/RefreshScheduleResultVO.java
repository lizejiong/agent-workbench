package com.lzj.rag.backend.refresh.controller.vo;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 文档刷新计划和最近一次运行状态。
 *
 * @param documentId 文档唯一标识。
 * @param enabled 是否启用该资源或能力。
 * @param cron 刷新计划的 Cron 表达式。
 * @param timezone 解释刷新 Cron 表达式所使用的明确时区。
 * @param version 当前记录版本或本次请求固定的版本标记。
 * @param nextRunAt 任务重试或计划下次执行时间。
 * @param lastStatus 最近一次刷新运行状态。
 * @param lastReason 最近一次刷新跳过或失败原因。
 * @param lastRunId 最近一次刷新运行标识。
 * @param lastContentHash 已成功发布源文件的内容摘要。
 * @param consecutiveFailures 刷新计划连续失败次数。
 */
public record RefreshScheduleResultVO(
        UUID documentId,
        boolean enabled,
        String cron,
        String timezone,
        long version,
        OffsetDateTime nextRunAt,
        String lastStatus,
        String lastReason,
        UUID lastRunId,
        String lastContentHash,
        int consecutiveFailures) {}
