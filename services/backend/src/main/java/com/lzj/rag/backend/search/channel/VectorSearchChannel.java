package com.lzj.rag.backend.search.channel;

import com.lzj.rag.backend.search.service.KnowledgeSearchService;
import com.lzj.rag.backend.search.service.bo.SearchBO;
import com.lzj.rag.backend.search.service.bo.SearchCandidateBO;
import com.lzj.rag.backend.search.service.bo.SearchCandidatesBO;
import com.lzj.rag.backend.security.model.KnowledgeBasePrincipal;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

/** 向量召回通道，将业务证据转换为带通道排名的候选集合。 */
@Component
public class VectorSearchChannel implements SearchChannel {

    /** 校验检索范围并执行向量召回的业务服务。 */
    private final KnowledgeSearchService service;

    /**
     * 初始化当前组件，保存业务依赖及本次使用的配置。
     *
     * @param service 校验检索范围并执行向量召回的业务服务。
     */
    public VectorSearchChannel(KnowledgeSearchService service) {
        this.service = service;
    }

    /**
     * 返回稳定的召回通道名称。
     *
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public String name() {
        return "VECTOR";
    }

    /**
     * 调用向量召回并转换为带原始分数和通道排名的候选。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param request 当前入口接收的请求参数。
     * @param id 当前业务对象的唯一标识。
     * @param limit 当前查询数量、下载字节数或解析字符预算的上限。
     * @param deadline 本阶段或本次请求必须完成的截止时间。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public SearchCandidatesBO search(
            KnowledgeBasePrincipal actor,
            SearchBO request,
            String id,
            int limit,
            Instant deadline) {
        // rows：数据库结果或当前表格数据行集合。
        var rows = service.recall(actor, request, id, limit, deadline);
        // result：当前步骤产生的处理结果。
        var result = new ArrayList<SearchCandidateBO>();
        // rank：候选在当前通道中的排名，从一开始计数。
        int rank = 0;
        // row：当前数据库记录或表格数据行。
        for (var row : rows)
            result.add(
                    new SearchCandidateBO(
                            row, name(), ++rank, row.score(), row.scoreType(), 0, null));
        return new SearchCandidatesBO(result, false);
    }
}
