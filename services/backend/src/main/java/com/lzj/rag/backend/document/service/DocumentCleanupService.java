package com.lzj.rag.backend.document.service;

import com.lzj.rag.backend.document.service.bo.DocumentCleanupBO;
import com.lzj.rag.backend.security.model.KnowledgeBasePrincipal;

import org.springframework.transaction.*;

import java.time.*;
import java.util.*;

/** 可供 HTTP、调度和内部调用共同使用的业务服务契约。 */
public interface DocumentCleanupService {

    /**
     * 锁定并校验文档版本，写入删除标记和持久化清理任务。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param id 当前业务对象的唯一标识。
     * @param version 当前记录版本或本次请求固定的版本标记。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    DocumentCleanupBO delete(KnowledgeBasePrincipal actor, UUID id, long version);

    /**
     * 读取当前用户可访问的文档清理任务详情。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param id 当前业务对象的唯一标识。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    DocumentCleanupBO get(KnowledgeBasePrincipal actor, UUID id);

    /**
     * 重置可重试的清理任务，保留有限重试策略。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param id 当前业务对象的唯一标识。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    DocumentCleanupBO retry(KnowledgeBasePrincipal actor, UUID id);

    /** 定时执行一批到期清理任务，单项失败不阻塞后续扫描。 */
    void scan();

    /**
     * 领取有效租约，删除文档关联数据及源对象，再按凭据提交清理结果。
     *
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    boolean runOnce();
}
