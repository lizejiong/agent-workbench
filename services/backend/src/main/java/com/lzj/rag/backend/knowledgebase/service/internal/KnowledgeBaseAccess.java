package com.lzj.rag.backend.knowledgebase.service.internal;

import com.lzj.rag.backend.knowledgebase.dao.entity.KnowledgeBaseDO;
import com.lzj.rag.backend.security.model.KnowledgeBasePrincipal;

import java.util.UUID;

/**
 * 后端内部的知识库实体访问契约，供事务编排和检索使用，不供 Controller 或外部工具入口调用。
 *
 * <p>与公开业务服务由同一组件实现，不单独创建事务，也不改变已有锁顺序。
 */
public interface KnowledgeBaseAccess {

    /** 按当前私有库政策解析默认检索范围；最多读取 limit 条，不做无界扫描。 */
    java.util.List<UUID> searchableIds(KnowledgeBasePrincipal actor, int limit);

    /** 返回当前租户与用户拥有且未删除的知识库；不存在或无权访问时报告未找到。 */
    KnowledgeBaseDO requireOwned(KnowledgeBasePrincipal actor, UUID id);

    /**
     * 在调用方已有的事务中锁定知识库行；后续需锁文档或任务时，必须先取得此锁。
     *
     * <p>返回的实体仅供当前内部流程使用，锁随调用方事务结束而释放。
     */
    KnowledgeBaseDO lockOwned(KnowledgeBasePrincipal actor, UUID id);
}
