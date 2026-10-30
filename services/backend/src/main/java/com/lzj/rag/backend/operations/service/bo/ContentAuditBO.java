package com.lzj.rag.backend.operations.service.bo;

import com.fasterxml.jackson.databind.*;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * 维护审计响应，包含操作人、切片集合和变更前后摘要。
 *
 * @param id 当前业务对象的唯一标识。
 * @param documentId 文档唯一标识。
 * @param actorId 执行维护操作的业务用户标识。
 * @param action 本次执行的业务操作名称或受限执行函数。
 * @param chunkIds 本次维护或审计涉及的切片标识集合。
 * @param beforeSummary 审计中记录的变更前摘要。
 * @param afterSummary 审计中记录的变更后摘要。
 * @param createdAt 记录创建时间。
 */
public record ContentAuditBO(
        UUID id,
        UUID documentId,
        String actorId,
        String action,
        JsonNode chunkIds,
        JsonNode beforeSummary,
        JsonNode afterSummary,
        OffsetDateTime createdAt) {}
