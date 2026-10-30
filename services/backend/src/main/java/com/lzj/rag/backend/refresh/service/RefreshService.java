package com.lzj.rag.backend.refresh.service;

import com.lzj.rag.backend.refresh.service.bo.RefreshBO;
import com.lzj.rag.backend.refresh.service.bo.RefreshClaimBO;
import com.lzj.rag.backend.refresh.service.bo.RefreshRunBO;
import com.lzj.rag.backend.refresh.service.bo.RefreshScheduleBO;
import com.lzj.rag.backend.refresh.service.bo.RefreshScheduleResultBO;
import com.lzj.rag.backend.refresh.service.bo.RefreshSubmittedBO;
import com.lzj.rag.backend.security.model.KnowledgeBasePrincipal;
import com.lzj.rag.framework.convention.page.PageResponse;

import org.springframework.transaction.*;

import java.time.*;
import java.util.*;

/** 可供 HTTP、调度和内部调用共同使用的业务服务契约。 */
public interface RefreshService {

    /**
     * 查询当前用户 URL 文档的刷新计划，缺省时初始化计划记录。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param document 本次操作涉及的文档唯一标识。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    RefreshScheduleResultBO schedule(KnowledgeBasePrincipal actor, UUID document);

    /**
     * 校验 Cron、时区和预期版本后修改刷新计划，取消已失效运行。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param document 本次操作涉及的文档唯一标识。
     * @param request 当前入口接收的请求参数。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    RefreshScheduleResultBO configure(
            KnowledgeBasePrincipal actor, UUID document, RefreshScheduleBO request);

    /**
     * 校验幂等键和人工内容覆盖确认，创建或复用手动刷新运行。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param document 本次操作涉及的文档唯一标识。
     * @param request 当前入口接收的请求参数。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    RefreshSubmittedBO submit(KnowledgeBasePrincipal actor, UUID document, RefreshBO request);

    /**
     * 在文档归属范围内分页读取刷新历史。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param document 本次操作涉及的文档唯一标识。
     * @param current 当前分页页码，从一开始。
     * @param size 分页容量或当前数据的字节数。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    PageResponse<RefreshRunBO> list(
            KnowledgeBasePrincipal actor, UUID document, int current, int size);

    /** 在统一业务锁下将到期计划转为持久化运行，并跳过当前不可刷新的文档。 */
    void enqueueDue();

    /**
     * 按本实例并发限制领取到期刷新，处理失效租约并生成新的栅栏令牌。
     *
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    RefreshClaimBO claimNext();

    /**
     * 校验一次性执行权，条件下载源文件，登记候选对象并提交后续入库任务。
     *
     * @param claim 当前后台任务的租约凭据或领取记录。
     */
    void execute(RefreshClaimBO claim);

    /** 只延长仍有效的刷新租约，不恢复过期租约且不超过运行截止时间。 */
    void heartbeat();

    /** 收敛输入已变化、预算耗尽或下游入库失败的刷新运行。 */
    void reconcile();
}
