package com.lzj.rag.backend.search.controller;

import com.lzj.rag.backend.search.config.HybridProperties;
import com.lzj.rag.backend.search.config.KeywordProperties;
import com.lzj.rag.backend.search.controller.request.SearchRequest;
import com.lzj.rag.backend.search.controller.vo.EvidenceVO;
import com.lzj.rag.backend.search.controller.vo.RetrievalVO;
import com.lzj.rag.backend.search.converter.SearchWebConverter;
import com.lzj.rag.backend.search.service.HybridRetrievalService;
import com.lzj.rag.backend.security.model.KnowledgeBasePrincipal;
import com.lzj.rag.framework.convention.result.Result;
import com.lzj.rag.framework.starter.web.context.RequestIdContext;
import com.lzj.rag.framework.starter.web.result.Results;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 检索 HTTP 入口，公开检索能力并统一调用混合检索编排。 */
@RestController
public class KnowledgeSearchController {

    /** 编排并行召回、融合重排和响应复核的业务服务。 */
    private final HybridRetrievalService search;

    /** 混合检索的召回容量、RRF 权重、通道预算和重排配置。 */
    private final HybridProperties config;

    /** 关键词检索开关、索引身份、分析器及同步工作线程配置。 */
    private final KeywordProperties keyword;

    /**
     * 初始化当前组件，保存业务依赖及本次使用的配置。
     *
     * @param search 编排并行召回、融合重排和响应复核的业务服务。
     * @param config 混合检索的召回容量、RRF 权重、通道预算和重排配置。
     * @param keyword 关键词检索开关、索引身份、分析器及同步工作线程配置。
     */
    public KnowledgeSearchController(
            HybridRetrievalService search, HybridProperties config, KeywordProperties keyword) {
        this.search = search;
        this.config = config;
        this.keyword = keyword;
    }

    /**
     * 校验并执行查询，按接口约定返回证据列表或完整检索响应。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param request 当前入口接收的请求参数。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @PostMapping("/api/knowledge/search")
    public ResponseEntity<Result<List<EvidenceVO>>> search(
            @AuthenticationPrincipal KnowledgeBasePrincipal actor,
            @Valid @RequestBody SearchRequest request) {
        // result：当前步骤产生的处理结果。
        var result =
                search.retrieve(
                        actor, SearchWebConverter.toSearchBO(request), RequestIdContext.get());
        // a：拼接响应头通道状态时的累计文本。
        // b：拼接响应头通道状态时的当前状态片段。
        // c：需要写入响应头的单个召回通道状态。
        // 集合按原顺序转换为接口响应。
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .header("X-KB-Search-Strategy", result.strategy())
                .header("X-KB-Rerank-Status", result.rerankStatus())
                .header("X-KB-Retrieval-Status", result.status())
                .header("X-KB-Retrieval-Outcome", result.outcome())
                .header(
                        "X-KB-Channel-Status",
                        result.channels().stream()
                                .map(c -> c.channel() + ":" + c.status())
                                .reduce((a, b) -> a + "," + b)
                                .orElse(""))
                .body(
                        Results.success(
                                result.evidence() == null
                                        ? null
                                        : result.evidence().stream()
                                                .map(SearchWebConverter::toEvidenceVO)
                                                .toList()));
    }

    /**
     * 返回包含策略、通道状态和追踪信息的完整检索结果。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param request 当前入口接收的请求参数。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @PostMapping("/api/knowledge/retrieve")
    public ResponseEntity<Result<RetrievalVO>> retrieve(
            @AuthenticationPrincipal KnowledgeBasePrincipal actor,
            @Valid @RequestBody SearchRequest request) {
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(
                        Results.success(
                                SearchWebConverter.toRetrievalVO(
                                        search.retrieve(
                                                actor,
                                                SearchWebConverter.toSearchBO(request),
                                                RequestIdContext.get()))));
    }

    /**
     * 返回当前启用的召回策略、重排能力及公开限制。
     *
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @GetMapping("/api/knowledge/search-capabilities")
    public Result<Map<String, Object>> capabilities() {
        return Results.success(
                Map.of(
                        "defaultStrategy",
                        config.strategy(),
                        "keywordConfigured",
                        keyword.enabled(),
                        "keywordAnalyzer",
                        keyword.analyzer(),
                        "keywordScoreType",
                        "BM25",
                        "rerankConfigured",
                        config.rerank().enabled(),
                        "rerankModel",
                        config.rerank().model(),
                        "channelCandidates",
                        config.channelCandidates(),
                        "fusionCandidates",
                        config.fusionCandidates(),
                        "answerEnabled",
                        false));
    }
}
