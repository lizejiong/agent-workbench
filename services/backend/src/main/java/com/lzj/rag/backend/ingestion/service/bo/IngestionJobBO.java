package com.lzj.rag.backend.ingestion.service.bo;

import java.time.*;
import java.util.*;

/**
 * 入库任务及租约、重试和错误状态的只读响应。
 *
 * @param id 当前业务对象的唯一标识。
 * @param requestId 请求跟踪标识或业务幂等键，按当前接口约定使用。
 * @param documentId 文档唯一标识。
 * @param documentConfigRevision 入库提交时固定的文档处理配置修订。
 * @param targetGeneration 本次入库准备发布的新代次。
 * @param jobType 后台任务类型。
 * @param status 当前业务操作或任务状态。
 * @param stage 当前流水线阶段标识。
 * @param attempts 已发生的执行尝试次数。
 * @param maxAttempts 允许的最大执行尝试次数。
 * @param createdAt 记录创建时间。
 * @param startedAt 任务或阶段首次开始执行的时间。
 * @param completedAt 任务完成或进入终态的时间。
 * @param nextRunAt 任务重试或计划下次执行时间。
 * @param leaseUntil 数据库记录的租约有效截止时间。
 * @param errorCode 可供调用方和运维识别的稳定错误码。
 * @param errorMessage 对外展示的错误说明，不包含内部连接或源文件内容。
 * @param aiConfigRevision 本次实际使用或任务固定的 AI 配置修订摘要。
 * @param retryOf 显式重试所对应的原任务标识。
 */
public record IngestionJobBO(
        UUID id,
        String requestId,
        UUID documentId,
        long documentConfigRevision,
        long targetGeneration,
        String jobType,
        String status,
        String stage,
        int attempts,
        int maxAttempts,
        OffsetDateTime createdAt,
        OffsetDateTime startedAt,
        OffsetDateTime completedAt,
        OffsetDateTime nextRunAt,
        OffsetDateTime leaseUntil,
        String errorCode,
        String errorMessage,
        String aiConfigRevision,
        UUID retryOf) {}
