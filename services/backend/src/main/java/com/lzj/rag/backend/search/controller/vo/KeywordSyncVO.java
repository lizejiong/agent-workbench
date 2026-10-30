package com.lzj.rag.backend.search.controller.vo;

import org.springframework.transaction.*;

import java.time.*;
import java.util.*;

/**
 * 关键词同步操作的状态、版本与错误信息。
 *
 * @param documentId 文档唯一标识。
 * @param revision 当前同步数据或配置使用的修订标识。
 * @param syncedRevision 已经成功发布到关键词索引的数据修订。
 * @param status 当前业务操作或任务状态。
 * @param attempts 已发生的执行尝试次数。
 * @param nextRunAt 任务重试或计划下次执行时间。
 * @param leaseUntil 数据库记录的租约有效截止时间。
 * @param errorCode 可供调用方和运维识别的稳定错误码。
 * @param syncedAt 关键词索引最近一次成功同步时间。
 * @param cleanupStatus 源对象补偿清理状态。
 * @param cleanupErrorCode 最近一次补偿清理的错误分类。
 */
public record KeywordSyncVO(
        UUID documentId,
        long revision,
        long syncedRevision,
        String status,
        int attempts,
        OffsetDateTime nextRunAt,
        OffsetDateTime leaseUntil,
        String errorCode,
        OffsetDateTime syncedAt,
        String cleanupStatus,
        String cleanupErrorCode) {}
