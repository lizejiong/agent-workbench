package com.lzj.rag.backend.search.service.impl;

import com.lzj.rag.backend.document.service.internal.DocumentAccess;
import com.lzj.rag.backend.knowledgebase.exception.KnowledgeBaseException;
import com.lzj.rag.backend.knowledgebase.service.internal.KnowledgeBaseAccess;
import com.lzj.rag.backend.search.budget.RetrievalDeadline;
import com.lzj.rag.backend.search.channel.SearchChannel;
import com.lzj.rag.backend.search.config.HybridProperties;
import com.lzj.rag.backend.search.config.KeywordProperties;
import com.lzj.rag.backend.search.config.SearchProperties;
import com.lzj.rag.backend.search.postprocess.SearchResultPostProcessor;
import com.lzj.rag.backend.search.service.HybridRetrievalService;
import com.lzj.rag.backend.search.service.KnowledgeSearchService;
import com.lzj.rag.backend.search.service.bo.ChannelScoreBO;
import com.lzj.rag.backend.search.service.bo.ChannelStateBO;
import com.lzj.rag.backend.search.service.bo.EvidenceBO;
import com.lzj.rag.backend.search.service.bo.IndexVersionBO;
import com.lzj.rag.backend.search.service.bo.RetrievalBO;
import com.lzj.rag.backend.search.service.bo.RetrievalTraceBO;
import com.lzj.rag.backend.search.service.bo.SearchBO;
import com.lzj.rag.backend.search.service.bo.SearchCandidateBO;
import com.lzj.rag.backend.search.service.bo.SearchCandidatesBO;
import com.lzj.rag.backend.security.model.KnowledgeBasePrincipal;

import jakarta.annotation.PreDestroy;

import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** 在统一预算内并行召回、融合重排，并在响应前复核授权与索引版本。 */
@Service
public class HybridRetrievalServiceImpl implements HybridRetrievalService {

    /** 校验检索范围并执行向量召回的业务服务。 */
    private final KnowledgeSearchService vector;

    /** 检索范围、查询长度、返回数量和总时间预算的限制。 */
    private final SearchProperties limits;

    /** 混合检索的召回容量、RRF 权重、通道预算和重排配置。 */
    private final HybridProperties config;

    /** 关键词检索开关、索引身份、分析器及同步工作线程配置。 */
    private final KeywordProperties keyword;

    /** 供内部流程校验知识库归属和取得行锁的访问契约。 */
    private final KnowledgeBaseAccess bases;

    /** 供内部流程校验文档归属和取得行锁的访问契约。 */
    private final DocumentAccess documents;

    /** 负责候选融合、重排及最终有效性过滤的组件。 */
    private final SearchResultPostProcessor post;

    /** 参与召回的通道集合或按名称索引的通道状态。 */
    private final Map<String, SearchChannel> channels = new HashMap<>();

    /** 容量受限的业务任务执行器。 */
    private final ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

    /**
     * 通道结果及单调时钟完成时刻，用于判断是否按时完成。
     *
     * @param result 当前步骤产生的处理结果。
     * @param finished 使用单调时钟记录的通道完成时刻。
     */
    private record Completed(SearchCandidatesBO result, long finished) {}

    /**
     * 运行通道、单调时钟截止点及可取消的 Future。
     *
     * @param name 当前业务资源或策略的公开名称。
     * @param deadline 本阶段或本次请求必须完成的截止时间。
     * @param future 当前异步操作的可等待结果，超时或失去执行权时用于取消任务。
     */
    private record Running(String name, long deadline, Future<Completed> future) {}

    /**
     * 初始化当前组件，保存业务依赖及本次使用的配置。
     *
     * @param vector 校验检索范围并执行向量召回的业务服务。
     * @param limits 检索范围、查询长度、返回数量和总时间预算的限制。
     * @param config 混合检索的召回容量、RRF 权重、通道预算和重排配置。
     * @param keyword 关键词检索开关、索引身份、分析器及同步工作线程配置。
     * @param bases 供内部流程校验知识库归属和取得行锁的访问契约。
     * @param documents 供内部流程校验文档归属和取得行锁的访问契约。
     * @param post 负责候选融合、重排及最终有效性过滤的组件。
     * @param channels 参与召回的通道集合或按名称索引的通道状态。
     */
    public HybridRetrievalServiceImpl(
            KnowledgeSearchService vector,
            SearchProperties limits,
            HybridProperties config,
            KeywordProperties keyword,
            KnowledgeBaseAccess bases,
            DocumentAccess documents,
            SearchResultPostProcessor post,
            List<SearchChannel> channels) {
        this.vector = vector;
        this.limits = limits;
        this.config = config;
        this.keyword = keyword;
        this.bases = bases;
        this.documents = documents;
        this.post = post;
        // channel：当前召回通道名称或通道实例。
        for (var channel : channels)
            if (this.channels.putIfAbsent(channel.name(), channel) != null)
                throw new IllegalArgumentException("Duplicate search channel");
        executor.setCorePoolSize(config.concurrency());
        executor.setMaxPoolSize(config.concurrency());
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("kb-retrieval-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
    }

    /**
     * 在总预算内确定策略、并行召回、融合重排并生成带降级说明的检索响应。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param request 当前入口接收的请求参数。
     * @param requestId 请求跟踪标识或业务幂等键，按当前接口约定使用。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public RetrievalBO retrieve(KnowledgeBasePrincipal actor, SearchBO request, String requestId) {
        return retrieve(actor, request, requestId, Instant.now().plus(limits.deadline()));
    }

    @Override
    public SearchBO prepare(KnowledgeBasePrincipal actor, SearchBO request, Instant deadline) {
        var budget = remaining(deadline);
        return phase(() -> {
            var resolved = resolveScope(actor, request);
            authorize(actor, resolved);
            return resolved;
        }, budget, budget.instant());
    }

    private RetrievalDeadline remaining(Instant deadline) {
        RetrievalDeadline.check(deadline);
        var duration = Duration.between(Instant.now(), deadline);
        return new RetrievalDeadline(duration.compareTo(limits.deadline()) < 0 ? duration : limits.deadline());
    }

    @Override
    public RetrievalBO retrieve(KnowledgeBasePrincipal actor, SearchBO request, String requestId, Instant outerDeadline) {
        // 第一阶段：建立共享总预算，补齐默认参数并校验全部访问范围。
        // budget：当前检索共享的总时间预算。
        var budget = remaining(outerDeadline);
        final SearchBO supplied = request;
        request = phase(() -> resolveScope(actor, supplied), budget, budget.instant());
        if (request.minRerankScore() == null && config.rerank().minScore() != null)
            request =
                    new SearchBO(
                            request.query(),
                            request.kbIds(),
                            request.documentIds(),
                            request.topK(),
                            request.minScore(),
                            request.strategy(),
                            request.rerank(),
                            config.rerank().minScore());
        // effectiveRequest：已补齐默认参数并供异步任务使用的请求。
        final SearchBO effectiveRequest = request;
        // versions：响应前后用于比较的知识库索引版本快照。
        var versions = phase(() -> authorize(actor, effectiveRequest), budget, budget.instant());
        // 第二阶段：确定实际策略及可选能力，非法组合在召回前直接拒绝。
        // wanted：调用方指定或配置默认的检索策略。
        String wanted = request.strategy() == null ? config.strategy() : request.strategy();
        // strategy：本次实际执行的检索策略。
        String strategy =
                wanted.equals("AUTO") ? (keyword.enabled() ? "HYBRID" : "VECTOR") : wanted;
        if (!keyword.enabled() && !strategy.equals("VECTOR"))
            throw invalid("KB_KEYWORD_DISABLED", "Enable keyword service or select VECTOR");
        // rerank：本次请求是否启用模型重排。
        boolean rerank = request.rerank() == null ? config.rerank().enabled() : request.rerank();
        if (rerank && !config.rerank().enabled())
            throw invalid("KB_RERANK_DISABLED", "Rerank is not configured");
        if (request.minRerankScore() != null
                && (!rerank || !Double.isFinite(request.minRerankScore())))
            throw invalid(
                    "KB_RERANK_REQUIRED", "A finite rerank threshold requires enabled reranking");
        if (request.minScore() != null && !strategy.equals("VECTOR"))
            throw invalid(
                    "KB_AMBIGUOUS_THRESHOLD",
                    "minScore is a cosine threshold for VECTOR only; use minRerankScore with reranking for hybrid evidence");
        // 纯向量请求沿用既有评分与错误约定，可选通道未启用时仍可独立工作。
        if (request.kbIds().isEmpty()) {
            return response(actor, List.of(), wanted, "NONE", "DISABLED", List.of(),
                    0, 0, request, false, versions, requestId, budget);
        }
        if (strategy.equals("VECTOR") && !rerank) {
            // deadline：本阶段或本次请求必须完成的截止时间。
            Instant deadline = budget.cap(config.vectorTimeout());
            // rows：数据库结果或当前表格数据行集合。
            var rows =
                    phase(
                            () ->
                                    vector.recall(
                                            actor,
                                            effectiveRequest,
                                            requestId,
                                            effectiveRequest.topK(),
                                            deadline),
                            budget,
                            deadline);
            // result：当前步骤产生的处理结果。
            var result = new ArrayList<EvidenceBO>();
            // rank：候选在当前通道中的排名，从一开始计数。
            int rank = 0;
            // e：向量通道返回的当前切片证据。
            for (var e : rows)
                result.add(
                        new EvidenceBO(
                                e.chunkId(),
                                e.kbId(),
                                e.documentId(),
                                e.documentName(),
                                e.chunkIndex(),
                                e.generation(),
                                e.content(),
                                e.score(),
                                e.scoreType(),
                                e.sourceMetadata(),
                                e.fileUrl(),
                                null,
                                null,
                                List.of(
                                        new ChannelScoreBO(
                                                "VECTOR", ++rank, e.score(), e.scoreType()))));
            return response(
                    actor,
                    result,
                    wanted,
                    "VECTOR",
                    "DISABLED",
                    List.of(new ChannelStateBO("VECTOR", "SUCCEEDED", rows.size(), null)),
                    request.topK(),
                    request.topK(),
                    request,
                    false,
                    versions,
                    requestId,
                    budget);
        }
        // 第三阶段：计算召回与融合容量，为各通道分配受总预算约束的局部截止时间。
        // recall：本次各通道召回的候选数量。
        int recall = Math.max(config.channelCandidates(), request.topK());
        // fusion：融合候选数量或当前切片累计的 RRF 分数。
        int fusion = Math.max(config.fusionCandidates(), request.topK());
        // tasks：并行召回或后台执行任务集合。
        var tasks = new ArrayList<Running>();
        // states：每个召回通道的执行结果状态。
        var states = new ArrayList<ChannelStateBO>();
        // candidates：本次待领取的任务、可选解析策略或召回候选集合。
        var candidates = new ArrayList<SearchCandidateBO>();
        // capped：本次召回是否触及补充查询上限。
        boolean capped = false;
        try {
            // name：当前业务资源或策略的公开名称。
            for (String name :
                    strategy.equals("HYBRID") ? List.of("VECTOR", "KEYWORD") : List.of(strategy)) {
                // timeout：当前阶段允许使用的局部时间预算。
                Duration timeout =
                        name.equals("VECTOR") ? config.vectorTimeout() : config.keywordTimeout();
                // end：当前范围的结束位置或局部阶段截止点。
                long end = System.nanoTime() + Math.min(timeout.toNanos(), budget.remainingNanos());
                // deadline：本阶段或本次请求必须完成的截止时间。
                Instant deadline = budget.cap(timeout);
                try {
                    tasks.add(
                            new Running(
                                    name,
                                    end,
                                    executor.submit(
                                            () ->
                                                    new Completed(
                                                            channels.get(name)
                                                                    .search(
                                                                            actor,
                                                                            effectiveRequest,
                                                                            requestId,
                                                                            recall,
                                                                            deadline),
                                                            System.nanoTime()))));
                } catch (RejectedExecutionException busy) {
                    // busy：本次捕获或匹配的异常，用于保留业务分类及失败原因。
                    states.add(new ChannelStateBO(name, "FAILED", 0, "KB_SEARCH_CAPACITY"));
                }
            }
            // 按最早截止时间消费 Future，保证短预算通道及时被取消。
            // 先等待最早截止的通道，避免慢向量调用推迟关键词任务的超时取消。
            tasks.sort(Comparator.comparingLong(Running::deadline));
            // task：本次已提交的异步任务，用于等待、取消或跟踪完成情况。
            for (var task : tasks) {
                try {
                    // completed：已完成的通道结果及完成时间。
                    Completed completed =
                            task.future()
                                    .get(
                                            Math.max(
                                                    1,
                                                    Math.min(
                                                            budget.remainingNanos(),
                                                            task.deadline() - System.nanoTime())),
                                            TimeUnit.NANOSECONDS);
                    if (completed.finished() > task.deadline()) throw new TimeoutException();
                    candidates.addAll(completed.result().candidates());
                    capped |= completed.result().refillLimitReached();
                    states.add(
                            new ChannelStateBO(
                                    task.name(),
                                    "SUCCEEDED",
                                    completed.result().candidates().size(),
                                    null));
                } catch (TimeoutException e) {
                    // e：向量通道返回的当前切片证据。
                    task.future().cancel(true);
                    states.add(
                            new ChannelStateBO(
                                    task.name(), "TIMEOUT", 0, "KB_SEARCH_CHANNEL_TIMEOUT"));
                } catch (ExecutionException e) {
                    // failure：当前需要传播或记录的业务失败。
                    // timedOut：当前调用是否由于截止时间或传输超时而失败。
                    // e：向量通道返回的当前切片证据。
                    boolean timedOut =
                            e.getCause() instanceof KnowledgeBaseException failure
                                    && failure.status() == 504;
                    // kb：本次捕获或匹配的异常，用于保留业务分类及失败原因。
                    states.add(
                            new ChannelStateBO(
                                    task.name(),
                                    timedOut ? "TIMEOUT" : "FAILED",
                                    0,
                                    e.getCause() instanceof KnowledgeBaseException kb
                                            ? kb.code()
                                            : "KB_SEARCH_CHANNEL_FAILED"));
                }
            }
        } catch (InterruptedException e) {
            // e：向量通道返回的当前切片证据。
            Thread.currentThread().interrupt();
            throw RetrievalDeadline.cancelled();
        } finally {
            tasks.forEach(
                    t -> {
                        // t：当前待取消或检查的通道任务及其 Future。
                        if (!t.future().isDone()) t.future().cancel(true);
                    });
        }
        budget.check();
        // 第四阶段：至少一个通道成功才进入后处理，失败通道作为降级信息保留。
        // s：当前召回通道的执行状态，用于统计成功及判断降级。
        // succeeded：成功完成的召回通道数量。
        long succeeded = states.stream().filter(s -> s.status().equals("SUCCEEDED")).count();
        if (succeeded == 0)
            throw new KnowledgeBaseException(
                    503, "KB_SEARCH_UNAVAILABLE", "All requested search channels failed");
        // 第五阶段：融合、可选重排与有效性复核共用剩余总预算。
        // processed：融合与重排完成后的证据及重排状态。
        var processed =
                phase(
                        () ->
                                post.process(
                                        actor,
                                        effectiveRequest,
                                        candidates,
                                        requestId,
                                        rerank,
                                        fusion,
                                        budget.instant()),
                        budget,
                        budget.instant());
        // a：拼接成功通道名称时的累计文本。
        // b：拼接成功通道名称时的当前通道名称。
        // s：当前召回通道的执行状态，用于统计成功及判断降级。
        // used：实际成功参与结果的召回通道名称组合。
        String used =
                states.stream()
                        .filter(s -> s.status().equals("SUCCEEDED"))
                        .map(ChannelStateBO::channel)
                        .sorted()
                        .reduce((a, b) -> a + "+" + b)
                        .orElseThrow();
        return response(
                actor,
                processed.evidence(),
                wanted,
                used,
                processed.rerankStatus(),
                states,
                recall,
                fusion,
                request,
                capped,
                versions,
                requestId,
                budget);
    }

    /**
     * 响应前复核授权和索引版本，区分有证据、确定无证据与降级导致的不确定结果。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param rows 数据库结果或当前表格数据行集合。
     * @param wanted 调用方指定或配置默认的检索策略。
     * @param used 实际成功参与结果的召回通道名称组合。
     * @param rerank 本次重排应用、跳过或失败降级的状态。
     * @param states 每个召回通道的执行结果状态。
     * @param recall 本次各通道召回的候选数量。
     * @param fusion 融合候选数量或当前切片累计的 RRF 分数。
     * @param request 当前入口接收的请求参数。
     * @param capped 本次召回是否触及补充查询上限。
     * @param versions 响应前后用于比较的知识库索引版本快照。
     * @param requestId 请求跟踪标识或业务幂等键，按当前接口约定使用。
     * @param budget 当前检索共享的总时间预算。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private RetrievalBO response(
            KnowledgeBasePrincipal actor,
            List<EvidenceBO> rows,
            String wanted,
            String used,
            String rerank,
            List<ChannelStateBO> states,
            int recall,
            int fusion,
            SearchBO request,
            boolean capped,
            List<IndexVersionBO> versions,
            String requestId,
            RetrievalDeadline budget) {
        // current：分页页码或当前处理的中间对象。
        var current = phase(() -> authorize(actor, request), budget, budget.instant());
        if (!versions.equals(current))
            throw new KnowledgeBaseException(
                    409,
                    "KB_SEARCH_SCOPE_CHANGED",
                    "Knowledge index configuration changed during retrieval; submit a new request");
        budget.check();
        // 响应分类：没有证据时，区分完整检索确实为空与降级导致的无法确定。
        // s：当前召回通道的执行状态，用于统计成功及判断降级。
        // degraded：是否存在通道失败、重排降级或召回补充上限等不完整情况。
        boolean degraded =
                states.stream().anyMatch(s -> !s.status().equals("SUCCEEDED"))
                        || Set.of("FALLBACK_FUSION", "THRESHOLD_UNAVAILABLE").contains(rerank)
                        || capped;
        // outcome：检索结果的证据确定性分类。
        String outcome = !rows.isEmpty() ? "EVIDENCE" : degraded ? "INDETERMINATE" : "NO_EVIDENCE";
        // trace：本次检索的策略、耗时和索引身份追踪信息。
        var trace =
                new RetrievalTraceBO(
                        requestId,
                        "retrieval-v1",
                        budget.elapsedMillis(),
                        versions,
                        used.contains("KEYWORD") ? keyword.target() : null,
                        rerank.equals("DISABLED") ? null : config.rerank().model());
        return new RetrievalBO(
                List.copyOf(rows),
                wanted,
                used,
                rerank,
                List.copyOf(states),
                recall,
                fusion,
                request.topK(),
                capped,
                request.minRerankScore(),
                degraded ? "DEGRADED" : "COMPLETE",
                outcome,
                trace);
    }

    /**
     * 将授权、向量召回或后处理作为受总预算约束的可取消任务执行。
     *
     * @param action 本次执行的业务操作名称或受限执行函数。
     * @param budget 当前检索共享的总时间预算。
     * @param deadline 本阶段或本次请求必须完成的截止时间。
     * @param <T> 当前业务结果或输入元素的类型。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private <T> T phase(Callable<T> action, RetrievalDeadline budget, Instant deadline) {
        budget.check();
        // future：当前异步操作的可等待结果，超时或失去执行权时用于取消任务。
        Future<T> future;
        try {
            future =
                    executor.submit(
                            () -> {
                                RetrievalDeadline.check(deadline);
                                return action.call();
                            });
        } catch (RejectedExecutionException busy) {
            // busy：本次捕获或匹配的异常，用于保留业务分类及失败原因。
            throw new KnowledgeBaseException(
                    503, "KB_SEARCH_CAPACITY", "Retrieval capacity is exhausted");
        }
        try {
            // result：当前步骤产生的处理结果。
            T result =
                    future.get(
                            Math.max(
                                    1,
                                    Math.min(
                                            budget.remainingNanos(),
                                            Duration.between(Instant.now(), deadline).toNanos())),
                            TimeUnit.NANOSECONDS);
            RetrievalDeadline.check(deadline);
            budget.check();
            return result;
        } catch (TimeoutException e) {
            // e：向量通道返回的当前切片证据。
            throw RetrievalDeadline.timeout();
        } catch (InterruptedException e) {
            // e：向量通道返回的当前切片证据。
            Thread.currentThread().interrupt();
            throw RetrievalDeadline.cancelled();
        } catch (ExecutionException e) {
            // runtime：从异步执行结果中恢复的运行时异常。
            // e：向量通道返回的当前切片证据。
            if (e.getCause() instanceof RuntimeException runtime) throw runtime;
            // error：本次处理捕获的异常，用于归类失败原因。
            if (e.getCause() instanceof Error error) throw error;
            throw new KnowledgeBaseException(
                    503, "KB_SEARCH_UNAVAILABLE", "Retrieval dependency failed");
        } finally {
            if (!future.isDone()) future.cancel(true);
        }
    }

    /**
     * 校验查询及范围参数，检查知识库和文档归属并返回索引版本快照。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param request 当前入口接收的请求参数。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private List<IndexVersionBO> authorize(KnowledgeBasePrincipal actor, SearchBO request) {
        // query：检索查询文本或已生成的查询向量。
        String query = request.query() == null ? "" : request.query().strip();
        // c：当前处理的 Unicode 码点或 SAX 字符片段。
        if (query.isBlank()
                || query.codePointCount(0, query.length()) > limits.maxQueryChars()
                || query.codePoints().anyMatch(c -> c == 0 || c >= 0xd800 && c <= 0xdfff)
                || request.topK() == null
                || request.topK() < 1
                || request.topK() > limits.maxTopK()
                || !request.isMinScoreValid()
                || request.strategy() != null
                        && !Set.of("AUTO", "VECTOR", "KEYWORD", "HYBRID")
                                .contains(request.strategy()))
            throw invalid("KB_INVALID_INPUT", "Check query, topK, strategy and score limits");
        if (request.kbIds() == null || !request.kbIds().isEmpty())
            checkIds(request.kbIds(), limits.maxKbIds(), false);
        checkIds(request.documentIds(), limits.maxDocumentIds(), true);
        // versions：响应前后用于比较的知识库索引版本快照。
        var versions = new ArrayList<IndexVersionBO>();
        // kb：本次操作涉及的知识库唯一标识。
        for (UUID kb : request.kbIds()) {
            // base：当前操作涉及的知识库及其固定模型绑定。
            var base = bases.requireOwned(actor, kb);
            versions.add(
                    new IndexVersionBO(
                            base.getId(),
                            base.getEmbeddingModelId(),
                            base.getEmbeddingConfigRevision(),
                            base.getEmbeddingAiConfigRevision()));
        }
        if (request.documentIds() != null)
            // doc：本次操作涉及的文档唯一标识。
            for (UUID doc : request.documentIds())
                if (!request.kbIds().contains(documents.requireOwned(actor, doc).getKbId()))
                    throw invalid(
                            "KB_SEARCH_DOCUMENT_SCOPE",
                            "Every document must belong to a selected knowledge base");
        return List.copyOf(versions);
    }

    /**
     * 校验范围标识集合的必填性、数量上限和唯一性。
     *
     * @param ids 本次操作涉及的业务对象标识集合。
     * @param max 当前校验允许的最大长度或数值。
     * @param optional 当前范围参数是否允许省略。
     */
    private SearchBO resolveScope(KnowledgeBasePrincipal actor, SearchBO request) {
        if (request.kbIds() != null) return request;
        var ids = bases.searchableIds(actor, limits.maxKbIds() + 1);
        if (ids.size() > limits.maxKbIds())
            throw invalid("KB_SEARCH_SCOPE_LIMIT", "Select explicit knowledge bases within the scope limit");
        return new SearchBO(request.query(), List.copyOf(ids), request.documentIds(), request.topK(),
                request.minScore(), request.strategy(), request.rerank(), request.minRerankScore());
    }

    private static void checkIds(List<UUID> ids, int max, boolean optional) {
        if (ids == null && optional) return;
        if (ids == null
                || ids.isEmpty()
                || ids.size() > max
                || ids.stream().anyMatch(Objects::isNull)
                || new HashSet<>(ids).size() != ids.size())
            throw invalid(
                    "KB_INVALID_INPUT",
                    "Resource ids must be nonempty, unique and within configured limits");
    }

    /**
     * 按给定分类创建检索参数错误。
     *
     * @param code 对外使用的稳定业务错误分类。
     * @param message 对外展示的业务错误或状态说明。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private static KnowledgeBaseException invalid(String code, String message) {
        return new KnowledgeBaseException(400, code, message);
    }

    /** 释放当前对象持有的临时文件或执行资源。 */
    @PreDestroy
    void close() {
        executor.shutdown();
    }
}
