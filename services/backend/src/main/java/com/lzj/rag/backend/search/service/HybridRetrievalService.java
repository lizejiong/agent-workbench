package com.lzj.rag.backend.search.service;

import com.lzj.rag.backend.search.service.bo.RetrievalBO;
import com.lzj.rag.backend.search.service.bo.SearchBO;
import com.lzj.rag.backend.security.model.KnowledgeBasePrincipal;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** 可供 HTTP、调度和内部调用共同使用的业务服务契约。 */
public interface HybridRetrievalService {

    /**
     * 在总预算内确定策略、并行召回、融合重排并生成带降级说明的检索响应。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param request 当前入口接收的请求参数。
     * @param requestId 请求跟踪标识或业务幂等键，按当前接口约定使用。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    RetrievalBO retrieve(KnowledgeBasePrincipal actor, SearchBO request, String requestId);

    RetrievalBO retrieve(KnowledgeBasePrincipal actor, SearchBO request, String requestId, Instant deadline);

    SearchBO prepare(KnowledgeBasePrincipal actor, SearchBO request, Instant deadline);

    RetrievalBO retrieveQueries(KnowledgeBasePrincipal actor, SearchBO request, List<String> queries, String requestId, Instant deadline);
}
