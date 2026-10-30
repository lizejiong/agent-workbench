package com.lzj.rag.backend.search.service.bo;

import java.util.*;

/**
 * 知识库本次检索使用的索引版本快照。
 *
 * @param kbId 知识库唯一标识。
 * @param embeddingModelId 固定嵌入模型的逻辑标识。
 * @param embeddingConfigRevision 后端嵌入配置的稳定修订摘要。
 * @param aiConfigRevision 本次实际使用或任务固定的 AI 配置修订摘要。
 */
public record IndexVersionBO(
        UUID kbId,
        String embeddingModelId,
        String embeddingConfigRevision,
        String aiConfigRevision) {}
