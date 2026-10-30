package com.lzj.rag.backend.search.repository.vector.dto;

import com.lzj.rag.backend.security.model.KnowledgeBasePrincipal;

import java.util.*;

/**
 * 向量操作的权限和命名空间范围。
 *
 * @param actor 本次业务操作的租户及用户身份。
 * @param kbId 知识库唯一标识。
 * @param namespace 向量数据隔离命名空间。
 * @param modelId 模型逻辑标识。
 * @param configRevision 业务处理配置修订，用于识别任务输入是否已失效。
 */
public record VectorScopeDTO(
        KnowledgeBasePrincipal actor,
        UUID kbId,
        String namespace,
        String modelId,
        String configRevision) {}
