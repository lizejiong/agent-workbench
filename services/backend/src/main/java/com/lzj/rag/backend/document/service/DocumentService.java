package com.lzj.rag.backend.document.service;

import com.lzj.rag.backend.document.dao.entity.DocumentDO;
import com.lzj.rag.backend.document.service.bo.DocumentBO;
import com.lzj.rag.backend.document.service.bo.DocumentPatchBO;
import com.lzj.rag.backend.document.service.bo.DocumentUploadedBO;
import com.lzj.rag.backend.document.service.bo.UploadSourceBO;
import com.lzj.rag.backend.security.model.KnowledgeBasePrincipal;
import com.lzj.rag.framework.convention.page.PageResponse;

import java.util.*;

/** 可供 HTTP、调度和内部调用共同使用的业务服务契约。 */
public interface DocumentService {

    /**
     * 按本地文件来源执行文档上传业务。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param kb 本次操作涉及的知识库唯一标识。
     * @param file 调用方上传的文件输入，后续按流读取并检查大小及格式。
     * @param rawMetadata 客户端上传的原始元数据 JSON。
     * @param key 对象存储键、配置键或业务幂等键。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    DocumentUploadedBO upload(
            KnowledgeBasePrincipal actor,
            UUID kb,
            UploadSourceBO file,
            String rawMetadata,
            String key);

    /**
     * 校验源文件与处理配置，预留上传操作后存储对象并提交文档记录。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param kb 本次操作涉及的知识库唯一标识。
     * @param file 调用方上传的文件输入，后续按流读取并检查大小及格式。
     * @param rawMetadata 客户端上传的原始元数据 JSON。
     * @param key 对象存储键、配置键或业务幂等键。
     * @param sourceUrl 远程文档配置的原始源地址。
     * @param onCreated 文档成功创建后在当前业务流程内调用的回调。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    DocumentUploadedBO uploadSource(
            KnowledgeBasePrincipal actor,
            UUID kb,
            UploadSourceBO file,
            String rawMetadata,
            String key,
            String sourceUrl,
            java.util.function.Consumer<DocumentBO> onCreated);

    /**
     * 按归属、处理状态和名称筛选条件分页查询文档。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param kb 本次操作涉及的知识库唯一标识。
     * @param current 当前分页页码，从一开始。
     * @param size 分页容量或当前数据的字节数。
     * @param name 当前业务资源或策略的公开名称。
     * @param status 当前业务操作或任务状态。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    PageResponse<DocumentBO> list(
            KnowledgeBasePrincipal actor,
            UUID kb,
            int current,
            int size,
            String name,
            String status);

    /**
     * 获取当前用户拥有的文档响应。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param id 当前业务对象的唯一标识。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    DocumentBO get(KnowledgeBasePrincipal actor, UUID id);

    /**
     * 校验预期版本后更新文档元信息或处理配置，配置变更时推进修订。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param id 当前业务对象的唯一标识。
     * @param request 当前入口接收的请求参数。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    DocumentBO patch(KnowledgeBasePrincipal actor, UUID id, DocumentPatchBO request);

    /**
     * 查找当前租户与用户拥有且未删除的文档。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param id 当前业务对象的唯一标识。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    DocumentDO requireOwned(KnowledgeBasePrincipal actor, UUID id);

    /**
     * 锁定调用者拥有的文档，供后续事务变更使用。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param id 当前业务对象的唯一标识。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    DocumentDO lockOwned(KnowledgeBasePrincipal actor, UUID id);

    /**
     * 将文档实体及处理配置转换为对外响应。
     *
     * @param d 本次操作涉及的文档实体。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    DocumentBO view(DocumentDO d);
}
