package com.lzj.rag.backend.document.controller.vo;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * 上传操作及失败补偿的可查询状态。
 *
 * @param id 当前业务对象的唯一标识。
 * @param idempotencyKey 防止重复提交的业务幂等键。
 * @param status 当前业务操作或任务状态。
 * @param attempts 已发生的执行尝试次数。
 * @param nextRunAt 任务重试或计划下次执行时间。
 * @param leaseUntil 数据库记录的租约有效截止时间。
 * @param errorCode 可供调用方和运维识别的稳定错误码。
 * @param updatedAt 记录最近更新时间。
 */
public record UploadOperationResultVO(
        UUID id,
        String idempotencyKey,
        String status,
        int attempts,
        OffsetDateTime nextRunAt,
        OffsetDateTime leaseUntil,
        String errorCode,
        OffsetDateTime updatedAt) {}
