package com.lzj.rag.backend.ingestion.service;

import com.lzj.rag.backend.ingestion.service.bo.IngestionContextBO;
import com.lzj.rag.backend.ingestion.service.bo.IngestionJobBO;
import com.lzj.rag.backend.ingestion.service.bo.IngestionStageBO;
import com.lzj.rag.backend.ingestion.service.bo.IngestionSubmissionBO;
import com.lzj.rag.backend.security.model.KnowledgeBasePrincipal;
import com.lzj.rag.framework.convention.page.PageResponse;

import java.time.*;
import java.util.*;

/** 可供 HTTP、调度和内部调用共同使用的业务服务契约。 */
public interface IngestionJobService {

    /**
     * 校验幂等键和人工内容覆盖确认，复用既有提交或创建持久化入库任务。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param documentId 文档唯一标识。
     * @param requestId 请求跟踪标识或业务幂等键，按当前接口约定使用。
     * @param retryOf 显式重试所对应的原任务标识。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    IngestionSubmissionBO submit(
            KnowledgeBasePrincipal actor, UUID documentId, String requestId, UUID retryOf);

    /**
     * 校验幂等键和人工内容覆盖确认，复用既有提交或创建持久化入库任务。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param documentId 文档唯一标识。
     * @param requestId 请求跟踪标识或业务幂等键，按当前接口约定使用。
     * @param retryOf 显式重试所对应的原任务标识。
     * @param confirmOverwriteEdited 是否已显式确认覆盖人工维护的内容与启停设置。
     * @param expectedDocumentVersion 调用方已审阅并要求匹配的文档版本。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    IngestionSubmissionBO submit(
            KnowledgeBasePrincipal actor,
            UUID documentId,
            String requestId,
            UUID retryOf,
            boolean confirmOverwriteEdited,
            Long expectedDocumentVersion);

    /**
     * 基于刷新候选对象创建标准入库任务，并关联刷新运行与租约令牌。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param run 当前刷新运行记录或运行标识。
     * @param token 当前任务的租约栅栏令牌或清理凭据。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    UUID submitRefresh(KnowledgeBasePrincipal actor, Map<String, Object> run, long token);

    /**
     * 为内部同步入口创建单次尝试任务，并立即领取相同的数据库执行凭据。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param documentId 文档唯一标识。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    IngestionContextBO start(KnowledgeBasePrincipal actor, UUID documentId);

    /**
     * 以非阻塞锁领取到期任务，先收敛失效租约，再创建新的执行尝试。
     *
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    IngestionContextBO claimNext();

    /**
     * 以数据库条件更新确保同一执行凭据只进入流水线一次。
     *
     * @param c 本次入库尝试固定的输入快照及租约凭据。
     */
    void enter(IngestionContextBO c);

    /**
     * 仅延长本工作线程仍有效的租约，截止时间之后不能恢复执行权。
     *
     * @param c 本次入库尝试固定的输入快照及租约凭据。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    boolean renew(IngestionContextBO c);

    /**
     * 工作线程拒绝接收任务时释放尚未进入流水线的领取记录。
     *
     * @param c 本次入库尝试固定的输入快照及租约凭据。
     */
    void releaseUnstarted(IngestionContextBO c);

    /**
     * 在当前有效执行凭据下记录阶段开始时间及输入计数。
     *
     * @param c 本次入库尝试固定的输入快照及租约凭据。
     * @param stage 当前流水线阶段标识。
     * @param inputs 当前阶段累计处理的输入数量。
     * @param unit 计数或来源行号所使用的单位。
     */
    void begin(IngestionContextBO c, String stage, long inputs, String unit);

    /**
     * 在当前有效执行凭据下记录阶段完成时间及输出计数。
     *
     * @param c 本次入库尝试固定的输入快照及租约凭据。
     * @param stage 当前流水线阶段标识。
     * @param outputs 当前阶段累计生成的输出数量。
     * @param unit 计数或来源行号所使用的单位。
     */
    void end(IngestionContextBO c, String stage, long outputs, String unit);

    /**
     * 将本次实际使用的 AI 配置修订固定到任务，防止尝试内身份漂移。
     *
     * @param c 本次入库尝试固定的输入快照及租约凭据。
     * @param revision 当前同步数据或配置使用的修订标识。
     */
    void pinAiRevision(IngestionContextBO c, String revision);

    /**
     * 在仍持有租约时归类执行错误，决定退避重试或进入终态。
     *
     * @param c 本次入库尝试固定的输入快照及租约凭据。
     * @param error 本次处理捕获的异常，用于归类失败原因。
     */
    void fail(IngestionContextBO c, RuntimeException error);

    /**
     * 校验归属并使活动任务进入取消状态，令旧执行凭据立即失效。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param jobId 持久化入库任务标识。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    IngestionJobBO cancel(KnowledgeBasePrincipal actor, UUID jobId);

    /**
     * 在文档业务锁内收敛已过期的运行尝试。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param documentId 文档唯一标识。
     */
    void recoverExpired(KnowledgeBasePrincipal actor, UUID documentId);

    /**
     * 为未发布或失效代次登记可重试的清理任务。
     *
     * @param documentId 文档唯一标识。
     * @param generation 切片或任务所属的数据代次。
     */
    void queueCleanup(UUID documentId, long generation);

    /**
     * 在当前用户及可选文档范围内读取任务详情。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param jobId 持久化入库任务标识。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    IngestionJobBO getOwnedJob(KnowledgeBasePrincipal actor, UUID jobId);

    /**
     * 在当前用户及可选文档范围内读取任务详情。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param documentId 文档唯一标识。
     * @param jobId 持久化入库任务标识。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    IngestionJobBO getOwnedJob(KnowledgeBasePrincipal actor, UUID documentId, UUID jobId);

    /**
     * 按文档与任务状态分页查询当前用户的入库任务。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param documentId 文档唯一标识。
     * @param current 当前分页页码，从一开始。
     * @param size 分页容量或当前数据的字节数。
     * @param status 当前业务操作或任务状态。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    PageResponse<IngestionJobBO> list(
            KnowledgeBasePrincipal actor, UUID documentId, int current, int size, String status);

    /**
     * 分页读取指定入库任务各次尝试的阶段记录。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param documentId 文档唯一标识。
     * @param jobId 持久化入库任务标识。
     * @param current 当前分页页码，从一开始。
     * @param size 分页容量或当前数据的字节数。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    PageResponse<IngestionStageBO> stages(
            KnowledgeBasePrincipal actor, UUID documentId, UUID jobId, int current, int size);
}
