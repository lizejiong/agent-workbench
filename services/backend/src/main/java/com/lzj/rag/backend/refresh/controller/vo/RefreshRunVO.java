package com.lzj.rag.backend.refresh.controller.vo;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 单次刷新运行的任务、下载、重试及耗时信息。
 *
 * @param id 当前业务对象的唯一标识。
 * @param documentId 文档唯一标识。
 * @param requestId 请求跟踪标识或业务幂等键，按当前接口约定使用。
 * @param triggerType 本次运行由手动请求或定时计划触发的类型。
 * @param status 当前业务操作或任务状态。
 * @param jobId 持久化入库任务标识。
 * @param attempts 已发生的执行尝试次数。
 * @param maxAttempts 允许的最大执行尝试次数。
 * @param httpStatus 远程源请求返回的 HTTP 状态。
 * @param reason 本次跳过、失败或状态迁移的原因分类。
 * @param errorMessage 对外展示的错误说明，不包含内部连接或源文件内容。
 * @param nextRunAt 任务重试或计划下次执行时间。
 * @param startedAt 任务或阶段首次开始执行的时间。
 * @param completedAt 任务完成或进入终态的时间。
 * @param durationMs 本次运行或阶段耗时，单位为毫秒。
 */
public record RefreshRunVO(
        UUID id,
        UUID documentId,
        String requestId,
        String triggerType,
        String status,
        UUID jobId,
        int attempts,
        int maxAttempts,
        Integer httpStatus,
        String reason,
        String errorMessage,
        OffsetDateTime nextRunAt,
        OffsetDateTime startedAt,
        OffsetDateTime completedAt,
        Long durationMs) {}
