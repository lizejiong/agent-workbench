package com.lzj.rag.backend.search.service.bo;

import org.springframework.transaction.*;

import java.time.*;
import java.util.*;

/**
 * 关键词同步尝试的任务和租约凭据。
 *
 * @param documentId 文档唯一标识。
 * @param kbId 知识库唯一标识。
 * @param token 当前任务的租约栅栏令牌或清理凭据。
 * @param revision 当前同步数据或配置使用的修订标识。
 * @param cleanup 是否执行历史索引清理，或当前对象清理任务记录。
 * @param deadline 本阶段或本次请求必须完成的截止时间。
 */
public record KeywordSyncClaimBO(
        UUID documentId, UUID kbId, long token, long revision, boolean cleanup, Instant deadline) {}
