package com.lzj.rag.backend.search.repository.keyword.dto;

import java.util.*;

/**
 * 写入关键词索引的切片及其权限和版本信息。
 *
 * @param id 当前业务对象的唯一标识。
 * @param chunkId 切片唯一标识。
 * @param kbId 知识库唯一标识。
 * @param documentId 文档唯一标识。
 * @param tenantId 数据所属租户标识。
 * @param ownerId 数据所有者的业务用户标识。
 * @param generation 切片或任务所属的数据代次。
 * @param syncRevision 本次关键词同步任务固定的数据修订。
 * @param versionKey 关键词索引中区分可见版本的复合键。
 * @param contentHash 源内容或切片正文的 SHA-256 摘要。
 * @param documentName 文档对外展示名称。
 * @param chunkIndex 切片在所属文档代次内的顺序位置。
 * @param content 用于展示或维护的正文内容。
 * @param enabled 是否启用该资源或能力。
 * @param chunkEnabled 切片是否允许参与检索。
 * @param documentEnabled 文档是否允许参与处理与检索。
 * @param source 当前结构块的来源字段或已校验临时源文件。
 */
public record KeywordChunkDTO(
        String id,
        UUID chunkId,
        UUID kbId,
        UUID documentId,
        String tenantId,
        String ownerId,
        long generation,
        long syncRevision,
        String versionKey,
        String contentHash,
        String documentName,
        int chunkIndex,
        String content,
        boolean enabled,
        boolean chunkEnabled,
        boolean documentEnabled,
        Map<String, Object> source) {}
