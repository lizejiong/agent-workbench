package com.lzj.rag.backend.search.postprocess;

import com.lzj.rag.backend.search.service.bo.SearchBO;
import com.lzj.rag.backend.search.service.bo.SearchCandidateBO;
import com.lzj.rag.backend.search.service.bo.SearchProcessedBO;
import com.lzj.rag.backend.security.model.KnowledgeBasePrincipal;

import java.time.Instant;
import java.util.*;

/** 候选融合与重排契约，处理过程受统一截止时间约束。 */
public interface SearchResultPostProcessor {

    /**
     * 在统一预算内完成候选融合、可选重排和最终有效性过滤。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param request 当前入口接收的请求参数。
     * @param candidates 本次待领取的任务、可选解析策略或召回候选集合。
     * @param requestId 请求跟踪标识或业务幂等键，按当前接口约定使用。
     * @param rerank 本次请求是否启用模型重排。
     * @param fusionLimit 参与后续重排的融合候选数量上限。
     * @param deadline 本阶段或本次请求必须完成的截止时间。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    SearchProcessedBO process(
            KnowledgeBasePrincipal actor,
            SearchBO request,
            List<SearchCandidateBO> candidates,
            String requestId,
            boolean rerank,
            int fusionLimit,
            Instant deadline);
}
