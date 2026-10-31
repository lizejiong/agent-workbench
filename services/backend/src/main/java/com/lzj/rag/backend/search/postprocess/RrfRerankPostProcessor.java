package com.lzj.rag.backend.search.postprocess;

import com.lzj.rag.backend.model.service.RerankClient;
import com.lzj.rag.backend.model.service.bo.RerankInputBO;
import com.lzj.rag.backend.model.service.bo.RerankScoreBO;
import com.lzj.rag.backend.search.budget.RetrievalDeadline;
import com.lzj.rag.backend.search.config.HybridProperties;
import com.lzj.rag.backend.search.repository.EvidenceRepository;
import com.lzj.rag.backend.search.service.bo.ChannelScoreBO;
import com.lzj.rag.backend.search.service.bo.EvidenceBO;
import com.lzj.rag.backend.search.service.bo.SearchBO;
import com.lzj.rag.backend.search.service.bo.SearchCandidateBO;
import com.lzj.rag.backend.search.service.bo.SearchProcessedBO;
import com.lzj.rag.backend.security.model.KnowledgeBasePrincipal;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

/** 按切片身份融合各通道排名，复核证据后执行可选重排与阈值过滤。 */
@Component
public class RrfRerankPostProcessor implements SearchResultPostProcessor {

    /** 混合检索的召回容量、RRF 权重、通道预算和重排配置。 */
    private final HybridProperties config;

    /** 在限定预算内为证据文档重排的客户端。 */
    private final RerankClient reranker;

    /** 从业务库重载当前有效证据及关键词版本的组件。 */
    private final EvidenceRepository records;

    /** 同一切片的融合状态，按通道去重并保存融合与重排分数。 */
    private static class Fused {

        /** 从当前有效代次加载的可公开切片证据。 */
        EvidenceBO evidence;

        /** 参与召回的通道集合或按名称索引的通道状态。 */
        final Map<String, SearchCandidateBO> channels = new TreeMap<>();

        /** 融合候选数量或当前切片累计的 RRF 分数。 */
        double fusion;

        /** 当前切片的模型重排分数，尚未重排时为空。 */
        Double rerank;

        /**
         * 初始化当前组件，保存业务依赖及本次使用的配置。
         *
         * @param evidence 从当前有效代次加载的可公开切片证据。
         */
        Fused(EvidenceBO evidence) {
            this.evidence = evidence;
        }
    }

    /**
     * 初始化当前组件，保存业务依赖及本次使用的配置。
     *
     * @param config 混合检索的召回容量、RRF 权重、通道预算和重排配置。
     * @param reranker 在限定预算内为证据文档重排的客户端。
     * @param records 从业务库重载当前有效证据及关键词版本的组件。
     */
    public RrfRerankPostProcessor(
            HybridProperties config, RerankClient reranker, EvidenceRepository records) {
        this.config = config;
        this.reranker = reranker;
        this.records = records;
    }

    /**
     * 依据各通道排名及权重计算 RRF 分数，不直接混合不同通道原始分数。
     *
     * @param fused 按切片身份聚合后的候选或融合候选集合。
     */
    private void score(Fused fused) {
        // c：当前切片在单个召回通道中的候选及原始排名。
        fused.fusion =
                fused.channels.values().stream()
                        .mapToDouble(
                                c ->
                                        (c.channel().equals("VECTOR")
                                                        ? config.vectorWeight()
                                                        : config.keywordWeight())
                                                / (config.rrfConstant() + c.rank()))
                        .sum();
    }

    /**
     * 按切片去重并融合排名，过滤失效证据后执行重排、阈值筛选和最终排序。
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
    public SearchProcessedBO process(
            KnowledgeBasePrincipal actor,
            SearchBO request,
            List<SearchCandidateBO> candidates,
            String requestId,
            boolean rerank,
            int fusionLimit,
            Instant deadline) {
        RetrievalDeadline.check(deadline);
        // 第一阶段：按切片去重，每个通道仅保留该切片最好的原始排名。
        // byId：按切片唯一标识聚合的融合候选状态。
        var byId = new LinkedHashMap<UUID, Fused>();
        // candidate：当前需要领取的后台任务或参与融合的召回候选。
        for (var candidate : candidates) {
            // k：需要创建融合状态的切片唯一标识。
            // fused：按切片身份聚合后的候选或融合候选集合。
            var fused =
                    byId.computeIfAbsent(
                            candidate.evidence().chunkId(), k -> new Fused(candidate.evidence()));
            // a：同一切片在同一通道中的已有候选，用较小排名保留更优贡献。
            // b：同一切片在同一通道中的新到达候选，用较小排名保留更优贡献。
            fused.channels.merge(
                    candidate.channel()+":"+Objects.toString(candidate.subQuery(),""), candidate, (a, b) -> a.rank() <= b.rank() ? a : b);
        }
        // 第二阶段：以加权 RRF 计算可比较的融合分数，使用切片标识稳定打破平分。
        // f：当前按切片身份聚合的融合状态。
        // f：当前按切片身份聚合的融合状态。
        // order：融合候选的稳定排序规则。
        var order =
                Comparator.<Fused>comparingDouble(f -> f.fusion)
                        .reversed()
                        .thenComparing(f -> f.evidence.chunkId());
        byId.values().forEach(this::score);
        // fused：按切片身份聚合后的候选或融合候选集合。
        var fused =
                new ArrayList<>(byId.values().stream().sorted(order).limit(fusionLimit).toList());
        // 调用外部重排模型前先重载证据，避免发送失效或未经授权的索引正文。
        filter(actor, request, fused);
        fused.sort(order);
        // status：当前业务操作或任务状态。
        String status = rerank ? "SKIPPED_EMPTY" : "DISABLED";
        RetrievalDeadline.check(deadline);
        // 第三阶段：在剩余预算内执行可选重排，失败时明确标记融合降级或阈值不可用。
        if (rerank && !fused.isEmpty()) {
            try {
                // local：当前元素本地名、局部解析器或阶段截止时间。
                Instant local = Instant.now().plus(config.rerank().timeout());
                // modelDeadline：当前模型调用最终采用的截止时间。
                Instant modelDeadline = local.isBefore(deadline) ? local : deadline;
                // f：当前按切片身份聚合的融合状态。
                // ranking：模型重排返回的整组原始位置及分数。
                var ranking =
                        reranker.rerank(
                                request.query().strip(),
                                fused.stream()
                                        .map(
                                                f ->
                                                        new RerankInputBO(
                                                                f.evidence.chunkId(),
                                                                f.evidence.content()))
                                        .toList(),
                                requestId,
                                modelDeadline);
                RetrievalDeadline.check(modelDeadline);
                if (ranking.size() != fused.size()
                        || ranking.stream().map(RerankScoreBO::index).distinct().count()
                                != fused.size()) throw new IllegalStateException();
                // ranked：当前重排结果及其原始输入位置。
                for (var ranked : ranking) {
                    if (ranked.index() < 0
                            || ranked.index() >= fused.size()
                            || !Double.isFinite(ranked.score())) throw new IllegalStateException();
                    fused.get(ranked.index()).rerank = ranked.score();
                }
                status = "APPLIED";
            } catch (RuntimeException error) {
                // error：本次处理捕获的异常，用于归类失败原因。
                RetrievalDeadline.check(deadline);
                // f：当前按切片身份聚合的融合状态。
                fused.forEach(f -> f.rerank = null);
                status =
                        request.minRerankScore() == null
                                ? "FALLBACK_FUSION"
                                : "THRESHOLD_UNAVAILABLE";
            }
        }
        // 模型返回后再次重载名称、坐标和正文，过滤等待期间被禁用、修改或删除的内容。
        filter(actor, request, fused);
        RetrievalDeadline.check(deadline);
        // 第四阶段：最终有效性复核后，按实际评分来源过滤阈值并截取 topK。
        // f：当前按切片身份聚合的融合状态。
        // resultOrder：应用可选重排后的最终排序规则。
        Comparator<Fused> resultOrder =
                "APPLIED".equals(status)
                        ? Comparator.<Fused>comparingDouble(f -> f.rerank)
                                .reversed()
                                .thenComparing(order)
                        : order;
        if (request.minRerankScore() != null)
            // f：当前按切片身份聚合的融合状态。
            fused.removeIf(f -> f.rerank == null || f.rerank < request.minRerankScore());
        // result：当前步骤产生的处理结果。
        var result =
                fused.stream()
                        .sorted(resultOrder)
                        .limit(request.topK())
                        .map(
                                f -> {
                                    // f：当前按切片身份聚合的融合状态。
                                    // e：融合状态中刚经过数据库复核的当前切片证据。
                                    var e = f.evidence;
                                    // c：当前切片在单个召回通道中的候选及原始排名。
                                    return new EvidenceBO(
                                            e.chunkId(),
                                            e.kbId(),
                                            e.documentId(),
                                            e.documentName(),
                                            e.chunkIndex(),
                                            e.generation(),
                                            e.content(),
                                            f.fusion,
                                            "RRF",
                                            e.sourceMetadata(),
                                            e.fileUrl(),
                                            f.fusion,
                                            f.rerank,
                                            f.channels.values().stream()
                                                    .map(
                                                            c ->
                                                                    new ChannelScoreBO(
                                                                            c.channel(),
                                                                            c.rank(),
                                                                            c.rawScore(),
                                                                            c.scoreType(),c.subQuery()))
                                                    .toList());
                                })
                        .toList();
        return new SearchProcessedBO(result, status);
    }

    /**
     * 重载当前切片并剔除内容、代次或关键词修订失效的通道贡献。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param request 当前入口接收的请求参数。
     * @param fused 按切片身份聚合后的候选或融合候选集合。
     */
    private void filter(KnowledgeBasePrincipal actor, SearchBO request, List<Fused> fused) {
        // f：当前按切片身份聚合的融合状态。
        // ids：本次操作涉及的业务对象标识集合。
        var ids = fused.stream().map(f -> f.evidence.chunkId()).toList();
        // live：从业务数据库重载的当前有效证据集合。
        var live = records.hydrate(actor, request, ids);
        // revisions：当前切片对应的可见关键词修订集合。
        var revisions = records.validKeywordRevisions(actor, request, ids);
        fused.removeIf(
                f -> {
                    // f：当前按切片身份聚合的融合状态。
                    // current：业务数据库重新加载的当前有效切片证据。
                    var current = live.get(f.evidence.chunkId());
                    if (current == null) return true;
                    f.channels
                            .entrySet()
                            .removeIf(
                                    entry -> {
                                        // entry：当前处理的键值项。
                                        // candidate：当前需要领取的后台任务或参与融合的召回候选。
                                        var candidate = entry.getValue();
                                        return candidate.evidence().generation()
                                                        != current.generation()
                                                || !candidate
                                                        .evidence()
                                                        .content()
                                                        .equals(current.content())
                                                || candidate.channel().equals("KEYWORD")
                                                        && !Objects.equals(
                                                                candidate.syncRevision(),
                                                                revisions.get(current.chunkId()));
                                    });
                    f.evidence = current;
                    score(f);
                    return f.channels.isEmpty();
                });
    }
}
