package com.lzj.rag.backend.chunk.service;

import com.lzj.rag.backend.chunk.service.bo.ChunkChangedBO;
import com.lzj.rag.backend.chunk.service.bo.ChunkContentBO;
import com.lzj.rag.backend.chunk.service.bo.ChunkViewBO;
import com.lzj.rag.backend.document.dao.entity.DocumentDO;
import com.lzj.rag.backend.document.service.bo.DocumentBO;
import com.lzj.rag.backend.security.model.KnowledgeBasePrincipal;
import com.lzj.rag.framework.convention.page.PageResponse;

import org.springframework.transaction.*;

import java.time.*;
import java.util.*;

/** 可供 HTTP、调度和内部调用共同使用的业务服务契约。 */
public interface ChunkMaintenanceService {

    /**
     * 在当前有效代次内按启用状态分页查询切片。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param id 当前业务对象的唯一标识。
     * @param current 当前分页页码，从一开始。
     * @param size 分页容量或当前数据的字节数。
     * @param enabled 是否启用该资源或能力。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    PageResponse<ChunkViewBO> list(
            KnowledgeBasePrincipal actor, UUID id, int current, int size, Boolean enabled);

    /**
     * 在事务外计算新向量，再在锁内复核版本并保存人工切片变更。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param id 当前业务对象的唯一标识。
     * @param chunkId 切片唯一标识。
     * @param request 当前入口接收的请求参数。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    ChunkChangedBO save(
            KnowledgeBasePrincipal actor, UUID id, UUID chunkId, ChunkContentBO request);

    /**
     * 校验预期版本后删除指定切片及其向量，记录人工维护状态。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param id 当前业务对象的唯一标识。
     * @param chunkId 切片唯一标识。
     * @param version 当前记录版本或本次请求固定的版本标记。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    ChunkChangedBO delete(KnowledgeBasePrincipal actor, UUID id, UUID chunkId, long version);

    /**
     * 批量切换切片启用状态，恢复缺失向量并同步索引变更。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param id 当前业务对象的唯一标识。
     * @param chunkIds 本次维护或审计涉及的切片标识集合。
     * @param applyAll 是否对当前文档全部切片执行维护。
     * @param enabled 是否启用该资源或能力。
     * @param version 当前记录版本或本次请求固定的版本标记。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    ChunkChangedBO enabled(
            KnowledgeBasePrincipal actor,
            UUID id,
            List<UUID> chunkIds,
            boolean applyAll,
            boolean enabled,
            long version);

    /**
     * 切换文档启用状态，同时更新可检索范围并处理活动任务。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param id 当前业务对象的唯一标识。
     * @param enabled 是否启用该资源或能力。
     * @param version 当前记录版本或本次请求固定的版本标记。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    DocumentBO documentEnabled(
            KnowledgeBasePrincipal actor, UUID id, boolean enabled, long version);

    /**
     * 取消文档仍处于待执行或运行状态的入库任务。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param id 当前业务对象的唯一标识。
     */
    void cancelActive(KnowledgeBasePrincipal actor, UUID id);

    /**
     * 持久化本次维护的操作人、目标切片及变更前后摘要。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param doc 本次操作涉及的文档实体。
     * @param action 本次执行的业务操作名称或受限执行函数。
     * @param ids 本次操作涉及的业务对象标识集合。
     * @param before 维护操作执行前的状态摘要。
     * @param after 维护操作执行后的状态摘要或下一次触发的时间基准。
     */
    void audit(
            KnowledgeBasePrincipal actor,
            DocumentDO doc,
            String action,
            List<UUID> ids,
            Object before,
            Object after);

    /**
     * 生成文档启用、代次和人工维护状态的审计摘要。
     *
     * @param doc 本次操作涉及的文档实体。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    Map<String, Object> summary(DocumentDO doc);
}
