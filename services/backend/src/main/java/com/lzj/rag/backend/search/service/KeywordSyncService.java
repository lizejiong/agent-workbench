package com.lzj.rag.backend.search.service;

import com.lzj.rag.backend.search.service.bo.KeywordSyncBO;
import com.lzj.rag.backend.search.service.bo.KeywordSyncClaimBO;
import com.lzj.rag.backend.security.model.KnowledgeBasePrincipal;
import com.lzj.rag.framework.convention.page.PageResponse;

import org.springframework.transaction.*;

import java.time.*;
import java.util.*;

/** 可供 HTTP、调度和内部调用共同使用的业务服务契约。 */
public interface KeywordSyncService {

    /**
     * 读取当前用户知识库的关键词同步状态。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param doc 本次操作涉及的文档唯一标识。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    KeywordSyncBO get(KnowledgeBasePrincipal actor, UUID doc);

    /**
     * 在文档归属范围内分页查询关键词同步操作。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param kb 本次操作涉及的知识库唯一标识。
     * @param page 当前源页面编号或分页页码。
     * @param size 分页容量或当前数据的字节数。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    PageResponse<KeywordSyncBO> list(KnowledgeBasePrincipal actor, UUID kb, int page, int size);

    /**
     * 校验归属与当前版本后重新安排失败的同步任务。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param kb 本次操作涉及的知识库唯一标识。
     * @param doc 本次操作涉及的文档唯一标识。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    KeywordSyncBO retry(KnowledgeBasePrincipal actor, UUID kb, UUID doc);

    /**
     * 按业务锁顺序领取同步任务，并处理过期租约与失效输入版本。
     *
     * @param cleanup 是否执行历史索引清理，或当前对象清理任务记录。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    KeywordSyncClaimBO claimNext(boolean cleanup);

    /**
     * 领取并执行一次关键词同步任务。
     *
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    boolean runOnce();

    /**
     * 在租约保护下分批构建索引，复核版本后发布同步状态并回收旧版本。
     *
     * @param claim 当前后台任务的租约凭据或领取记录。
     */
    void execute(KeywordSyncClaimBO claim);

    /** 为本实例仍有效的关键词同步任务续租。 */
    void heartbeat();
}
