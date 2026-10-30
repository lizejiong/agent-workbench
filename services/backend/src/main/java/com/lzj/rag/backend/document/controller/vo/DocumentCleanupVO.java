package com.lzj.rag.backend.document.controller.vo;

import org.springframework.transaction.*;

import java.time.*;
import java.util.*;

/**
 * 文档删除任务的清理状态及重试信息。
 *
 * @param id 当前业务对象的唯一标识。
 * @param documentId 文档唯一标识。
 * @param status 当前业务操作或任务状态。
 * @param stage 当前流水线阶段标识。
 * @param attempts 已发生的执行尝试次数。
 * @param maxAttempts 允许的最大执行尝试次数。
 * @param nextRunAt 任务重试或计划下次执行时间。
 * @param leaseUntil 数据库记录的租约有效截止时间。
 * @param errorCode 可供调用方和运维识别的稳定错误码。
 * @param errorMessage 对外展示的错误说明，不包含内部连接或源文件内容。
 * @param createdAt 记录创建时间。
 * @param completedAt 任务完成或进入终态的时间。
 */
public record DocumentCleanupVO(
        UUID id,
        UUID documentId,
        String status,
        String stage,
        int attempts,
        int maxAttempts,
        OffsetDateTime nextRunAt,
        OffsetDateTime leaseUntil,
        String errorCode,
        String errorMessage,
        OffsetDateTime createdAt,
        OffsetDateTime completedAt) {}
