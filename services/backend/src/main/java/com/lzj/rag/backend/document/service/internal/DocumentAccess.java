package com.lzj.rag.backend.document.service.internal;

import com.lzj.rag.backend.document.dao.entity.DocumentDO;
import com.lzj.rag.backend.document.service.bo.DocumentBO;
import com.lzj.rag.backend.security.model.KnowledgeBasePrincipal;

import java.util.UUID;

/** 后端内部的文档实体访问与转换契约，不供 Controller 或外部工具入口调用。 */
public interface DocumentAccess {

    /** 校验文档及所属知识库的归属，返回未删除的文档；不取得行锁。 */
    DocumentDO requireOwned(KnowledgeBasePrincipal actor, UUID id);

    /**
     * 在调用方已有的事务中锁定文档行；调用方必须先锁定所属知识库，再按业务顺序锁定任务。
     *
     * <p>此方法仅校验文档归属，锁随调用方事务结束而释放。
     */
    DocumentDO lockOwned(KnowledgeBasePrincipal actor, UUID id);

    /** 将内部流程已授权读取的实体转换为业务结果；此转换本身不执行归属校验。 */
    DocumentBO view(DocumentDO document);
}
