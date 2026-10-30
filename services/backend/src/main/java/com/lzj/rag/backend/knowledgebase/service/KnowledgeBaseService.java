package com.lzj.rag.backend.knowledgebase.service;

import com.lzj.rag.backend.knowledgebase.dao.entity.KnowledgeBaseDO;
import com.lzj.rag.backend.knowledgebase.service.bo.KnowledgeBaseBO;
import com.lzj.rag.backend.knowledgebase.service.bo.KnowledgeBaseBindBO;
import com.lzj.rag.backend.knowledgebase.service.bo.KnowledgeBaseCreateBO;
import com.lzj.rag.backend.knowledgebase.service.bo.KnowledgeBasePatchBO;
import com.lzj.rag.backend.knowledgebase.service.bo.KnowledgeBaseUpdateBO;
import com.lzj.rag.backend.security.model.KnowledgeBasePrincipal;
import com.lzj.rag.framework.convention.page.PageResponse;

import java.util.UUID;

/** 可供 HTTP、调度和内部调用共同使用的业务服务契约。 */
public interface KnowledgeBaseService {

    /**
     * 规范化名称并校验可选模型绑定，创建属于当前用户的知识库。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param request 当前入口接收的请求参数。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    KnowledgeBaseBO create(KnowledgeBasePrincipal actor, KnowledgeBaseCreateBO request);

    /**
     * 在租户与所有者范围内分页查询知识库，可按名称筛选。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param current 当前分页页码，从一开始。
     * @param size 分页容量或当前数据的字节数。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    PageResponse<KnowledgeBaseBO> list(KnowledgeBasePrincipal actor, int current, int size);

    /**
     * 在租户与所有者范围内分页查询知识库，可按名称筛选。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param current 当前分页页码，从一开始。
     * @param size 分页容量或当前数据的字节数。
     * @param name 当前业务资源或策略的公开名称。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    PageResponse<KnowledgeBaseBO> list(
            KnowledgeBasePrincipal actor, int current, int size, String name);

    /**
     * 返回当前用户拥有的知识库及公开模型绑定信息。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param id 当前业务对象的唯一标识。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    KnowledgeBaseBO get(KnowledgeBasePrincipal actor, UUID id);

    /**
     * 按预期版本更新知识库信息，拒绝修改已有内容的固定模型身份。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param id 当前业务对象的唯一标识。
     * @param request 当前入口接收的请求参数。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    KnowledgeBaseBO update(KnowledgeBasePrincipal actor, UUID id, KnowledgeBaseUpdateBO request);

    /**
     * 校验版本及关联内容约束后删除知识库。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param id 当前业务对象的唯一标识。
     * @param version 当前记录版本或本次请求固定的版本标记。
     */
    void delete(KnowledgeBasePrincipal actor, UUID id, long version);

    /**
     * 查找当前租户与用户拥有且未删除的知识库。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param id 当前业务对象的唯一标识。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    KnowledgeBaseDO requireOwned(KnowledgeBasePrincipal actor, UUID id);

    /**
     * 按归属条件锁定知识库，作为文档与任务变更的首个业务锁。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param id 当前业务对象的唯一标识。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    KnowledgeBaseDO lockOwned(KnowledgeBasePrincipal actor, UUID id);

    /**
     * 在锁内按预期版本局部更新知识库信息与启用状态。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param id 当前业务对象的唯一标识。
     * @param request 当前入口接收的请求参数。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    KnowledgeBaseBO patch(KnowledgeBasePrincipal actor, UUID id, KnowledgeBasePatchBO request);

    /**
     * 为未绑定模型的知识库补充固定嵌入配置，并推进版本。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param id 当前业务对象的唯一标识。
     * @param request 当前入口接收的请求参数。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    KnowledgeBaseBO bindLegacy(KnowledgeBasePrincipal actor, UUID id, KnowledgeBaseBindBO request);
}
