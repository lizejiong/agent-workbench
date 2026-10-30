package com.lzj.rag.backend.search.channel;

import com.lzj.rag.backend.search.service.bo.SearchBO;
import com.lzj.rag.backend.search.service.bo.SearchCandidatesBO;
import com.lzj.rag.backend.security.model.KnowledgeBasePrincipal;

import java.time.Instant;
import java.util.*;

/** 独立召回通道契约，结果保留通道排名、原始分数和索引同步版本。 */
public interface SearchChannel {

    /**
     * 返回稳定的召回通道名称。
     *
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    String name();

    /**
     * 在给定权限范围和截止时间内召回有限候选，返回是否达到补充查询上限。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param request 当前入口接收的请求参数。
     * @param requestId 请求跟踪标识或业务幂等键，按当前接口约定使用。
     * @param limit 当前查询数量、下载字节数或解析字符预算的上限。
     * @param deadline 本阶段或本次请求必须完成的截止时间。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    SearchCandidatesBO search(
            KnowledgeBasePrincipal actor,
            SearchBO request,
            String requestId,
            int limit,
            Instant deadline);
}
