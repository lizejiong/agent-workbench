package com.lzj.rag.backend.operations.service;

import com.fasterxml.jackson.databind.*;
import com.lzj.rag.backend.operations.service.bo.ContentAuditBO;
import com.lzj.rag.backend.security.model.KnowledgeBasePrincipal;
import com.lzj.rag.framework.convention.page.PageResponse;

import java.util.*;

/** 管理导航和权限范围内的维护审计查询契约。 */
public interface ManagementService {

    /**
     * 返回管理入口及服务能力导航。
     *
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    Map<String, Object> entry();

    /**
     * 在知识库归属范围内分页读取维护审计记录。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param kbId 知识库唯一标识。
     * @param current 当前分页页码，从一开始。
     * @param size 分页容量或当前数据的字节数。
     * @param action 本次执行的业务操作名称或受限执行函数。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    PageResponse<ContentAuditBO> audit(
            KnowledgeBasePrincipal actor, UUID kbId, int current, int size, String action);
}
