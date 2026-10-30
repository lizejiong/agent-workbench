package com.lzj.rag.backend.search.service;

import com.fasterxml.jackson.databind.*;
import com.lzj.rag.backend.search.service.bo.EvidenceBO;
import com.lzj.rag.backend.search.service.bo.SearchBO;
import com.lzj.rag.backend.security.model.KnowledgeBasePrincipal;

import java.time.Instant;
import java.util.*;

/** 可供 HTTP、调度和内部调用共同使用的业务服务契约。 */
public interface KnowledgeSearchService {

    /**
     * 执行受统一时间预算约束的向量检索。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param request 当前入口接收的请求参数。
     * @param requestId 请求跟踪标识或业务幂等键，按当前接口约定使用。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    List<EvidenceBO> search(KnowledgeBasePrincipal actor, SearchBO request, String requestId);

    /**
     * 校验范围与嵌入绑定兼容性，计算查询向量并加载有效切片证据。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param request 当前入口接收的请求参数。
     * @param requestId 请求跟踪标识或业务幂等键，按当前接口约定使用。
     * @param limit 当前查询数量、下载字节数或解析字符预算的上限。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    List<EvidenceBO> recall(
            KnowledgeBasePrincipal actor, SearchBO request, String requestId, int limit);

    /**
     * 校验范围与嵌入绑定兼容性，计算查询向量并加载有效切片证据。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param request 当前入口接收的请求参数。
     * @param requestId 请求跟踪标识或业务幂等键，按当前接口约定使用。
     * @param limit 当前查询数量、下载字节数或解析字符预算的上限。
     * @param deadline 本阶段或本次请求必须完成的截止时间。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    List<EvidenceBO> recall(
            KnowledgeBasePrincipal actor,
            SearchBO request,
            String requestId,
            int limit,
            Instant deadline);

    /**
     * 校验并清理来源元数据，仅保留允许公开的定位字段。
     *
     * @param raw 尚未校验或规范化的原始输入。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    Map<String, Object> source(String raw);
}
