package com.lzj.rag.ai.model.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lzj.rag.ai.infra.provider.ProviderClient;
import com.lzj.rag.ai.infra.provider.dto.ProviderModelDTO;
import com.lzj.rag.ai.infra.provider.dto.ProviderRankingDTO;
import com.lzj.rag.ai.infra.provider.exception.ModelInvocationException;
import com.lzj.rag.ai.model.converter.ModelProviderConverter;
import com.lzj.rag.ai.model.enums.ModelCapability;
import com.lzj.rag.ai.model.resilience.RequestGate;
import com.lzj.rag.ai.model.routing.ModelRegistry;
import com.lzj.rag.ai.model.service.ModelService;
import com.lzj.rag.ai.model.service.bo.*;
import com.lzj.rag.ai.model.service.bo.ChatBO.ImageBO;
import com.lzj.rag.ai.model.service.bo.RerankResultBO.RankedBO;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.function.Function;
import org.slf4j.*;
import org.springframework.stereotype.Service;
import reactor.core.publisher.*;

/**
 * 模型业务编排：输入校验、路由、预算控制、降级与结果归一化。
 */
@Service
public class ModelServiceImpl implements ModelService {

    /**
     * 只记录请求标识、模型、分类和耗时的日志器。
     */
    private static final Logger log = LoggerFactory.getLogger(ModelServiceImpl.class);

    /**
     * 模型注册器，提供配置快照、候选路由和能力检查。
     */
    private final ModelRegistry registry;

    /**
     * 执行供应商 HTTP/SSE 请求并适配原生协议的客户端。
     */
    private final ProviderClient provider;

    /**
     * 逻辑请求并发入口与按模型划分的熔断租约管理器。
     */
    private final RequestGate gate;

    /**
     * JSON 对象解析器与构造器。
     */
    private final ObjectMapper json;

    /**
     * 注入模型注册器、供应商客户端、并发与熔断入口以及 JSON 解析器。
     *
     * @param registry 模型注册器，提供配置快照、候选路由和能力检查。
     * @param provider 执行供应商 HTTP/SSE 请求并适配原生协议的客户端。
     * @param gate 逻辑请求并发入口与按模型划分的熔断租约管理器。
     * @param json JSON 对象解析器与构造器。
     */
    public ModelServiceImpl(ModelRegistry registry, ProviderClient provider, RequestGate gate, ObjectMapper json) {
        this.registry = registry;
        this.provider = provider;
        this.gate = gate;
        this.json = json;
    }

    /**
     * 查询可公开的嵌入模型描述与配置修订，不执行付费供应商探测。
     *
     * @param alias 应用内部的逻辑模型别名，不是供应商原生模型名。
     * @param dimensions 本次向量结果使用的维度。
     * @return 与目标维度对应的模型元数据。
     */
    @Override
    public EmbeddingModelBO embeddingModel(String alias, Integer dimensions) {
        return registry.embeddingDescriptor(alias, dimensions);
    }

    /**
     * 校验输入后按有序候选执行对话，在共享预算内降级，并返回完整业务结果。
     *
     * @param request 当前操作的输入请求数据。
     * @param vision 是否按视觉请求校验图片并要求候选具备视觉能力。
     * @param requestId 贯穿接口、业务调用、供应商请求与日志的关联 ID。
     * @return 包含正文、工具、用量与调用统计的业务结果。
     */
    @Override
    public Mono<ChatResultBO> chat(ChatBO request, boolean vision, String requestId) {
        return Mono.defer(() -> {
            // 第一阶段：所有入口共用输入规则，再确定满足能力要求的候选。
            validateChat(request, vision);
            // candidates：保持配置优先级的可用逻辑模型别名。
            var candidates = registry.chat(request, vision);
            // run：跨候选共享的总预算、尝试次数和统计状态。
            var run = new Run(requestId);

            // 第二阶段：在逻辑请求的并发许可和总期限内依次尝试。
            // alias：本次实际调用的逻辑模型别名，与供应商原生模型名称区分。
            return logical(run, () -> attempt(candidates, 0, run, alias ->
                    provider.chat(target(alias), ModelProviderConverter.toProvider(request), requestId)
                            .map(reply -> {
                                // reply：供应商归一化结果；JSON 模式仍需检查完整对象。
                                validateJson(request, reply.content(), !reply.tools().isEmpty());

                                // 第三阶段：补上实际模型及整个逻辑请求的统计。
                                return new ChatResultBO(
                                        reply.content(), reply.reasoning(),
                                        reply.tools().stream().map(ModelProviderConverter::toBO).toList(),
                                        alias, reply.finish(),
                                        ModelProviderConverter.toBO(reply.usage()), run.metrics());
                            })));
        });
    }

    /**
     * 执行流式模型调用；可见输出前允许降级，输出后只发送本次模型的结束或错误事件。
     *
     * @param request 当前操作的输入请求数据。
     * @param vision 是否按视觉请求校验图片并要求候选具备视觉能力。
     * @param requestId 贯穿接口、业务调用、供应商请求与日志的关联 ID。
     * @return 可被取消的业务事件流。
     */
    @Override
    public Flux<ModelEventBO> stream(ChatBO request, boolean vision, String requestId) {
        return Flux.defer(() -> {
            // 第一阶段：订阅时校验输入并建立一次逻辑请求的状态。
            validateChat(request, vision);
            // candidates：已经按能力、开关和凭据过滤的有序候选。
            var candidates = registry.chat(request, vision);
            // run：所有候选共享；visible 一旦置位，后续错误就不能换模型。
            var run = new Run(requestId);

            // 第二阶段：using 在完成、失败和取消时都释放并发名额。
            // permit：本次逻辑请求的许可；error：流执行阶段收到的失败。
            return Flux.using(
                    gate::enter,
                    permit -> streamAttempt(candidates, 0, run, request)
                            .onErrorResume(error -> run.visible
                                    ? Flux.just(event("error", run, Map.of(
                                            "code", ModelInvocationException.classify(error).category(),
                                            "message", ModelInvocationException.classify(error).getMessage())))
                                    : Flux.error(error)),
                    ModelServiceImpl::close);
        });
    }

    /**
     * 执行一个流式候选：申请熔断租约、等待首个有效片段、输出事件、完成计数，再决定是否换候选。
     *
     * @param candidates 已按优先级排序并过滤可用性的逻辑模型别名列表。
     * @param index 本次应尝试的候选在有序候选列表中的位置。
     * @param run 当前逻辑请求共享的预算、可见性与尝试统计状态。
     * @param request 当前操作的输入请求数据。
     * @return 本次候选的事件流，或在尚无可见输出时续接的下一候选流。
     */
    private Flux<ModelEventBO> streamAttempt(List<String> candidates, int index, Run run, ChatBO request) {
        return Flux.defer(() -> {
            // 第一阶段：检查候选和尝试次数；所有候选共享 run 的总期限。
            if (index >= candidates.size() || run.attempts >= registry.config().limits().maxAttempts())
                return Flux.error(run.last == null
                        ? ModelInvocationException.unavailable("No available model") : run.last);

            // alias：当前逻辑模型；budget：当前候选时限与总剩余预算的较小值。
            String alias = candidates.get(index);
            Duration budget = registry.model(alias).timeout() == null
                    ? remaining(run) : min(remaining(run), registry.model(alias).timeout());
            // lease：本次模型尝试的熔断租约，携带取得时的熔断代次。
            RequestGate.Lease lease;
            try {
                lease = gate.acquire(alias);
            } catch (ModelInvocationException open) {
                // open：熔断打开的提示；跳过候选，不占实际调用次数。
                run.last = open;
                return streamAttempt(candidates, index + 1, run, request);
            }

            // 第二阶段：实际取得租约后才计数，并创建当前候选独有的累计状态。
            // attemptNumber：实际尝试序号；切换候选后日志仍使用本次固定序号。
            int attemptNumber = ++run.attempts;
            run.model = alias;
            // attemptError：供失败与最终日志回调共享的单元素异常容器。
            ModelInvocationException[] attemptError = {null};
            // attemptStart：候选开始时的单调时钟纳秒值。
            long attemptStart = System.nanoTime();
            // content：只在 JSON 模式累计正文，结束时校验完整对象。
            StringBuilder content = new StringBuilder();
            // tools：回调间共享的标记，表示本次流中出现过工具调用。
            boolean[] tools = {false};
            // finish：回调间共享的供应商完成原因，不从连接关闭推断。
            String[] finish = {null};

            // 第三阶段：忽略空角色帧和心跳，等待首个真正有效的输出。
            // source：完整候选流；chunk：首片段计时器回调参数，到达后不重置首次时限。
            // t：总预算到达时的定时器信号，其数值本身不参与业务。
            var source = provider.stream(target(alias), ModelProviderConverter.toProvider(request), run.id)
                    .map(ModelProviderConverter::toBO)
                    .skipUntil(ModelChunkBO::meaningful)
                    .timeout(
                            Mono.delay(min(budget, registry.config().limits().firstTokenTimeout())),
                            chunk -> Mono.never())
                    .switchOnFirst((signal, sameFlux) -> {
                        // signal：首片段通知；sameFlux：仍包含首片段的原流，不能漏掉首项。
                        if (signal.hasError())
                            return Flux.error(signal.getThrowable());
                        if (!signal.hasValue())
                            return Flux.error(ModelInvocationException.invalid());

                        // 第四阶段：即将发出可见事件，从此不再允许切换模型。
                        run.firstTokenMs = run.elapsed();
                        run.visible = true;
                        return Flux.concat(
                                Flux.just(event("start", run, Map.of("attempt", run.attempts))),
                                sameFlux.timeout(registry.config().limits().streamIdleTimeout())
                                        .handle((chunk, sink) -> {
                                            // chunk：当前业务片段；sink：向下游发出转换后事件的入口。
                                            if (chunk.type().equals("finish")) {
                                                finish[0] = chunk.finishReason();
                                                return;
                                            }
                                            if (chunk.type().equals("content") && Boolean.TRUE.equals(request.json()))
                                                content.append(chunk.text());
                                            if (chunk.type().equals("tool"))
                                                tools[0] = true;

                                            // payload：工具和用量保留结构化对象，文本转换为增量载荷。
                                            Object payload = switch (chunk.type()) {
                                                case "tool" -> chunk.tool();
                                                case "usage" -> chunk.usage();
                                                default -> Map.of("text", chunk.text());
                                            };
                                            sink.next(event(chunk.type(), run, payload));
                                        }),
                                Flux.defer(() -> {
                                    // 尾部统一检查 JSON；用量尾帧消费完成后才发出 done。
                                    validateJson(request, content.toString(), tools[0]);
                                    return Flux.just(event("done", run, Map.of(
                                            "finishReason", finish[0], "metrics", run.metrics())));
                                }));
                    })
                    .takeUntilOther(Mono.delay(budget).flatMap(t -> Mono.error(ModelInvocationException.timeout())));

            // 第五阶段：结算租约并记录耗时；最终关闭也覆盖调用方取消的情况。
            return source.doOnComplete(lease::success)
                    .doOnError(error -> {
                        // error：当前候选失败原因，先归类，再决定是否计入熔断。
                        run.last = ModelInvocationException.classify(error);
                        attemptError[0] = run.last;
                        lease.failure(run.last.counted());
                    })
                    .doFinally(signal -> {
                        // signal：完成、失败或取消信号；租约只会被实际结算一次。
                        lease.close();
                        progress(run, alias, attemptNumber, attemptError[0], attemptStart, signal.name());
                    })
                    .onErrorResume(error -> {
                        // error：本次失败；failure：归类后用于判断降级资格的异常。
                        ModelInvocationException failure = ModelInvocationException.classify(error);
                        if (run.visible || !failure.retryable())
                            return Flux.error(failure);

                        // 只有尚无可见输出且错误允许重试时，才递归尝试下一个候选。
                        return streamAttempt(candidates, index + 1, run, request);
                    });
        });
    }

    /**
     * 固定向量身份，按批次调用候选；只有整组完成并通过校验才返回结果。
     *
     * @param request 当前操作的输入请求数据。
     * @param requestId 贯穿接口、业务调用、供应商请求与日志的关联 ID。
     * @return 按原始输入顺序排列、且身份一致的完整向量结果。
     */
    @Override
    public Mono<EmbedResultBO> embed(EmbedBO request, String requestId) {
        return Mono.defer(() -> {
            // 第一阶段：统一单条和批量输入，执行相同的条数与字符限制。
            if (request == null || (request.text() == null) == (request.texts() == null))
                throw ModelInvocationException.input("Use exactly one of text or texts");
            // texts：保持原始顺序的完整文本集合。
            List<String> texts = request.texts() == null ? List.of(request.text()) : request.texts();
            validateTexts(texts);

            // 第二阶段：确定向量身份；维度和配置修订必须在调用前固定。
            // anchor：锚点逻辑模型；model：锚点配置；dimensions：实际请求维度。
            String anchor = request.model() == null ? registry.config().embedding().defaultModel() : request.model();
            var model = registry.model(anchor);
            int dimensions = request.dimensions() == null
                    ? model.dimensions() == null ? 0 : model.dimensions() : request.dimensions();
            if (dimensions <= 0 || (model.allowedDimensions().isEmpty()
                    ? !Objects.equals(model.dimensions(), dimensions)
                    : !model.allowedDimensions().contains(dimensions)))
                throw ModelInvocationException.input("Unsupported embedding dimensions");
            // descriptor：与目标维度对应的公开嵌入身份，不含凭据。
            var descriptor = registry.embeddingDescriptor(anchor, dimensions);
            if (request.expectedConfigRevision() != null
                    && !request.expectedConfigRevision().equals(descriptor.configRevision()))
                throw new ModelInvocationException(409, "MODEL_CONFIG_CHANGED", "Embedding configuration changed", false, false);

            // 第三阶段：显式固定模型或修订时不降级；兼容降级仍需同空间、同实际维度。
            // candidates：本次允许的有序候选；a：候选别名；m：对应模型配置。
            List<String> candidates = registry.operation(
                    anchor,
                    request.model() != null || request.expectedConfigRevision() != null
                            ? List.of() : registry.config().embedding().fallbacks(),
                    ModelCapability.EMBEDDING)
                    .stream().filter(a -> {
                        var m = registry.model(a);
                        return Objects.equals(model.embeddingSpace(), m.embeddingSpace())
                                && (m.allowedDimensions().isEmpty()
                                        ? Objects.equals(m.dimensions(), dimensions)
                                        : m.allowedDimensions().contains(dimensions));
                    }).toList();
            registry.available(candidates);
            // run：所有候选和所有批次共同使用的一次逻辑请求状态。
            var run = new Run(requestId);

            // 第四阶段：每个候选都重新创建累计集合，禁止返回部分成功或混合向量。
            // alias：当前候选逻辑模型；一个候选失败后，下一候选从首批重新执行。
            return logical(run, () -> attempt(candidates, 0, run, alias -> {
                // size：当前模型的批次上限；all：整组向量；usages：各批次真实用量。
                int size = registry.model(alias).batchSize();
                List<EmbeddingVectorBO> all = new ArrayList<>();
                List<UsageBO> usages = new ArrayList<>();

                return Flux.range(0, (texts.size() + size - 1) / size).concatMap(batch -> {
                    // batch：零起始批次号；offset：全组偏移；group：当前文本子批次。
                    int offset = batch * size;
                    List<String> group = texts.subList(offset, Math.min(texts.size(), offset + size));
                    return provider.embed(target(alias), group, dimensions, requestId).doOnNext(reply -> {
                        // reply：供应商单批结果，先检查条数，再检查每个向量。
                        if (reply.vectors().size() != group.size())
                            throw ModelInvocationException.invalid();
                        // seen：当前批次索引是否已经出现，防止重复数据替代缺失结果。
                        boolean[] seen = new boolean[group.size()];
                        // vector：当前批内向量；供应商可以乱序返回，但索引不能越界或重复。
                        for (var vector : reply.vectors()) {
                            // i：供应商批次内索引，写入整组结果时还需要加 offset。
                            int i = vector.index();
                            // v：向量中的一个数值；分别检查有限性、浮点范围及全零情况。
                            if (i < 0 || i >= group.size() || seen[i]
                                    || vector.values().size() != dimensions
                                    || vector.values().stream().anyMatch(v ->
                                            v == null || !Double.isFinite(v) || Math.abs(v) > Float.MAX_VALUE)
                                    || vector.values().stream().allMatch(v -> v == 0d))
                                throw ModelInvocationException.invalid();
                            seen[i] = true;
                            all.add(new EmbeddingVectorBO(offset + i, vector.values()));
                        }
                        usages.add(ModelProviderConverter.toBO(reply.usage()));
                    });
                }).then(Mono.fromSupplier(() -> {
                    // 第五阶段：全部批次通过后恢复输入顺序，返回实际成功模型的身份。
                    all.sort(Comparator.comparingInt(EmbeddingVectorBO::index));
                    // actual：实际成功候选的嵌入身份，不能误用最初锚点的身份。
                    var actual = registry.embeddingDescriptor(alias, dimensions);
                    return new EmbedResultBO(
                            all, dimensions, alias, sum(usages), run.metrics(),
                            actual.provider(), actual.embeddingSpace(), actual.configRevision());
                }));
            }));
        });
    }

    /**
     * 校验文档身份与输入限制，一次重排整组文档，再稳定排序并截取结果。
     *
     * @param request 当前操作的输入请求数据。
     * @param requestId 贯穿接口、业务调用、供应商请求与日志的关联 ID。
     * @return 按分数降序、同分按原始位置升序的结果。
     */
    @Override
    public Mono<RerankResultBO> rerank(RerankBO request, String requestId) {
        return Mono.defer(() -> {
            if (request == null || request.query() == null || request.query().isBlank() || request.documents() == null)
                throw ModelInvocationException.input("query and documents are required");
            // 本次操作的有序文本输入集合。
            List<String> texts = request.documents().stream().map(d -> {
                // d：当前待重排文档，先检查其业务 ID 再取出原文。
                if (d == null || d.id() == null || d.id().isBlank())
                    throw ModelInvocationException.input("Document id is required");
                return d.text();
            }).toList();
            validateTexts(texts);
            if ((long) request.query().length() + texts.stream().mapToLong(String::length).sum() > registry.config().limits().maxInputChars())
                throw ModelInvocationException.input("Input is too large");
            // 最多返回的文档数，业务层将其限制在输入文档数以内。
            int topN = request.topN() == null ? Math.min(5, texts.size()) : Math.min(request.topN(), texts.size());
            if (topN <= 0)
                throw ModelInvocationException.input("topN must be positive");
            // 本次操作固定的首个逻辑模型，用于约束向量身份或优先顺序。
            String anchor = request.model() == null ? registry.config().rerank().defaultModel() : request.model();
            // 已按优先级排序并过滤可用性的逻辑模型别名列表。
            var candidates = registry.available(registry.operation(anchor, registry.config().rerank().fallbacks(), ModelCapability.RERANK));
            // 当前逻辑请求共享的预算、可见性与尝试统计状态。
            var run = new Run(requestId);
            // alias：当前实际尝试的逻辑模型；整组文档在一个请求中重排，不跨请求合并分数。
            return logical(run, () -> attempt(candidates, 0, run, alias ->
                    provider.rerank(target(alias), request.query(), texts, topN, requestId).map(reply -> {
                // reply：供应商本次调用返回的归一化结果。
                // 已处理索引的去重标记，防止重复或缺失结果混入输出。
                Set<Integer> seen = new HashSet<>();
                // score：当前文档的原始位置与相关性分数。
                for (var score : reply.scores()) if (score.index() < 0 || score.index() >= texts.size() || !seen.add(score.index()))
                    throw ModelInvocationException.invalid();
                if (reply.scores().size() < topN)
                    throw ModelInvocationException.invalid();
                // s：排序后待映射回原文档身份的一项相关性分数。
                // 当前处理的文档索引与相关性分数列表。
                var scores = reply.scores().stream()
                        .sorted(Comparator.comparingDouble(ProviderRankingDTO.ScoreDTO::score)
                                .reversed().thenComparingInt(ProviderRankingDTO.ScoreDTO::index))
                        .limit(topN)
                        .map(s -> new RankedBO(request.documents().get(s.index()).id(), s.index(), s.score()))
                        .toList();
                return new RerankResultBO(scores, alias, ModelProviderConverter.toBO(reply.usage()), run.metrics());
            })));
        });
    }

    /**
     * 为完整逻辑请求申请并发名额并施加总超时，结束、失败和取消都会释放名额。
     *
     * @param run 当前逻辑请求共享的预算、可见性与尝试统计状态。
     * @param operation 延迟执行的完整业务操作，使资源申请和异常进入响应式生命周期。
     * @param <T> 当前业务结果或输入元素的类型。
     * @return 受总预算和统一异常分类约束的业务结果。
     */
    private <T> Mono<T> logical(Run run, java.util.function.Supplier<Mono<T>> operation) {
        // permit：逻辑请求持有的并发许可，由 using 在结束或取消时自动释放。
        return Mono.using(
                gate::enter, permit -> Mono.defer(operation).timeout(remaining(run)), ModelServiceImpl::close)
                .onErrorMap(ModelInvocationException::classify);
    }

    /**
     * 执行一个非流式候选；熔断打开时跳过，只对实际发起的尝试计数。
     *
     * @param candidates 已按优先级排序并过滤可用性的逻辑模型别名列表。
     * @param index 本次应尝试的候选在有序候选列表中的位置。
     * @param run 当前逻辑请求共享的预算、可见性与尝试统计状态。
     * @param call 以逻辑模型别名为输入、延迟执行实际供应商调用的函数。
     * @param <T> 当前业务结果或输入元素的类型。
     * @return 首个成功候选的结果，或预算耗尽后的最后错误。
     */
    private <T> Mono<T> attempt(List<String> candidates, int index, Run run, Function<String, Mono<T>> call) {
        return Mono.defer(() -> {
            // 第一阶段：候选耗尽或实际尝试次数已满，返回最近一次具体错误。
            if (index >= candidates.size() || run.attempts >= registry.config().limits().maxAttempts())
                return Mono.error(run.last == null
                        ? ModelInvocationException.unavailable("No available model") : run.last);
            // alias：当前逻辑别名；timeout：模型单次时限；budget：与总剩余量取较小值。
            String alias = candidates.get(index);
            Duration timeout = registry.model(alias).timeout();
            Duration budget = timeout == null ? remaining(run) : min(timeout, remaining(run));
            // lease：当前候选的熔断租约，取得后必须结算或释放。
            RequestGate.Lease lease;
            try {
                lease = gate.acquire(alias);
            } catch (ModelInvocationException open) {
                // open：熔断尚未允许真实调用，跳过时不占实际尝试次数。
                run.last = open;
                return attempt(candidates, index + 1, run, call);
            }

            // 第二阶段：实际发起请求才记录尝试次数，时间预算不会随候选切换重置。
            // attemptNumber：本次固定序号；start：本次候选的单调时钟起点。
            int attemptNumber = ++run.attempts;
            run.model = alias;
            long start = System.nanoTime();
            // attemptError：供错误回调与最终日志共享的单元素容器。
            ModelInvocationException[] attemptError = {null};

            // 第三阶段：成功结算租约；失败先分类；最终关闭覆盖取消与非计数失败。
            // value：成功结果，本回调只更新熔断状态，不改写结果内容。
            return Mono.defer(() -> call.apply(alias)).timeout(budget)
                    .doOnSuccess(value -> lease.success())
                    .doOnError(error -> {
                        // error：本次候选的原始失败原因。
                        run.last = ModelInvocationException.classify(error);
                        attemptError[0] = run.last;
                        lease.failure(run.last.counted());
                    })
                    .doFinally(signal -> {
                        // signal：完成、失败或取消，用于记录本次尝试的终态。
                        lease.close();
                        progress(run, alias, attemptNumber, attemptError[0], start, signal.name());
                    })
                    .onErrorResume(error -> {
                        // error：本次失败；failure：归类后决定能否继续降级的异常。
                        ModelInvocationException failure = ModelInvocationException.classify(error);
                        return failure.retryable()
                                ? attempt(candidates, index + 1, run, call) : Mono.error(failure);
                    });
        });
    }

    /**
     * 校验对话、视觉和工具续接规则，使非 HTTP 入口也经过相同的输入检查。
     *
     * @param r 需要执行完整业务校验的对话输入。
     * @param vision 是否按视觉请求校验图片并要求候选具备视觉能力。
     */
    private void validateChat(ChatBO r, boolean vision) {
        if (r == null || (r.text() == null) == (r.messages() == null))
            throw ModelInvocationException.input("Use exactly one of text or messages");
        if (r.maxOutputTokens() != null && r.maxOutputTokens() <= 0)
            throw ModelInvocationException.input("maxOutputTokens must be positive");
        if (r.text() != null)
            validateTexts(List.of(r.text()));
        // 本次请求累计的图片数量，用于视觉入口与数量上限检查。
        // 第二阶段：逐条校验消息，并累计图片数和所有可计入限制的文本字符。
        int images = 0;
        // 消息正文、推理与工具参数累计占用的字符数。
        long chars = 0;
        if (r.messages() != null) {
            if (r.messages().isEmpty() || r.messages().size() > registry.config().limits().maxInputs())
                throw ModelInvocationException.input("Invalid messages count");
            // m：当前对话消息，需校验角色、内容、图片和工具续接。
            for (var m : r.messages()) {
                if (m == null || m.role() == null || !Set.of("system", "user", "assistant", "tool").contains(m.role()))
                    throw ModelInvocationException.input("Invalid message role");
                if ((m.content() == null
                        || m.content().isBlank())
                        && (m.images() == null
                        || m.images().isEmpty())
                        && (m.toolCalls() == null
                        || m.toolCalls().isEmpty()))
                    throw ModelInvocationException.input("Message must contain content, images or tool calls");
                chars += m.content() == null ? 0 : m.content().length();
                chars += m.reasoning() == null ? 0 : m.reasoning().length();
                if (m.role().equals("tool") && (m.toolCallId() == null || m.toolCallId().isBlank()))
                    throw ModelInvocationException.input("Tool message requires toolCallId");
                if (m.toolCalls() != null)
                    // c：当前消息携带的工具调用，需包含完整的 ID、名称与参数。
                    for (var c : m.toolCalls()) {
                        if (c == null || c.id() == null || c.id().isBlank() || c.name() == null || c.name().isBlank() || c.arguments() == null)
                            throw ModelInvocationException.input("Incomplete tool call");
                        chars += c.arguments().length();
                    }
                if (m.images() != null)
                    // image：当前消息中的图片引用或 Base64 内容。
                    for (var image : m.images()) {
                        validateImage(image);
                        images++;
                    }
            }
        }
        if (chars > registry.config().limits().maxInputChars())
            throw ModelInvocationException.input("Input is too large");
        if (images > registry.config().limits().maxImages())
            throw ModelInvocationException.input("Too many images");
        if (vision && images == 0)
            throw ModelInvocationException.input("Vision requires images");
        if (!vision && images > 0)
            throw ModelInvocationException.input("Use the vision endpoint for images");

        // 第三阶段：工具名必须唯一，参数只接收 JSON Schema 对象。
        if (r.tools() != null) {
            if (r.tools().size() > 64)
                throw ModelInvocationException.input("Too many tools");
            // 当前已经出现的工具名称，确保工具定义不重名。
            Set<String> names = new HashSet<>();
            // t：当前工具定义，名称需合法且唯一，参数必须是 JSON 对象。
            for (var t : r.tools()) if (t == null
                    || t.name() == null
                    || !t.name().matches("[A-Za-z0-9_-]{1,64}")
                    || !names.add(t.name())
                    || t.parameters() == null
                    || !t.parameters().isObject())
                throw ModelInvocationException.input("Invalid tool definition");
        }
        if (r.toolChoice() != null && (r.tools() == null || r.tools().isEmpty()))
            throw ModelInvocationException.input("toolChoice requires tools");
    }

    /**
     * 校验图片来源二选一、URL 格式、媒体类型和 Base64 解码大小。
     *
     * @param image 当前消息中的图片引用或 Base64 内容。
     */
    private void validateImage(ImageBO image) {
        if (image == null || (image.url() == null) == (image.base64() == null))
            throw ModelInvocationException.input("Image requires URL or base64");

        // URL 只验证后转发；Base64 才在本服务解码并检查实际字节数。
        if (image.url() != null) {
            try {
                // 解析后的图片或供应商地址，用于校验协议、主机和凭据信息。
                var uri = URI.create(image.url());
                if (!Set.of("http", "https").contains(uri.getScheme())
                        || uri.getHost() == null
                        || uri.getUserInfo() != null
                        || image.url().length() > 8192)
                    throw new IllegalArgumentException();
            } catch (RuntimeException e) {
                // e：图片地址或 Base64 编码校验失败的原因，转为输入错误。
                throw ModelInvocationException.input("Invalid image URL");
            }
        } else {
            if (image.mediaType() == null || !Set.of("image/png", "image/jpeg", "image/webp", "image/gif").contains(image.mediaType()))
                throw ModelInvocationException.input("Unsupported image mediaType");
            // 单张 Base64 图片解码后的最大允许字节数。
            int max = registry.config().limits().maxImageBytes();
            if (image.base64().length() > ((long) max + 2) / 3 * 4)
                throw ModelInvocationException.input("Image is too large");
            try {
                // Base64 解码后的图片字节，仅用于检查非空和大小。
                byte[] bytes = Base64.getDecoder().decode(image.base64());
                if (bytes.length == 0 || bytes.length > max)
                    throw new IllegalArgumentException();
            } catch (IllegalArgumentException e) {
                // e：图片地址或 Base64 编码校验失败的原因，转为输入错误。
                throw ModelInvocationException.input("Invalid image base64 or size");
            }
        }
    }

    /**
     * 校验文本非空、批量条数和累计字符上限。
     *
     * @param texts 本次操作的有序文本输入集合。
     */
    private void validateTexts(List<String> texts) {
        // 应用配置的输入、并发、熔断和时间限制。
        var l = registry.config().limits();
        // t：当前待校验文本，不能为 null 或全空白。
        if (texts == null || texts.isEmpty() || texts.size() > l.maxInputs() || texts.stream().anyMatch(t -> t == null || t.isBlank()))
            throw ModelInvocationException.input("Nonempty text inputs are required within configured count limit");
        if (texts.stream().mapToLong(String::length).sum() > l.maxInputChars())
            throw ModelInvocationException.input("Input is too large");
    }

    /**
     * JSON 模式下要求返回一个完整对象；工具回合无正文时允许只返回工具调用。
     *
     * @param request 当前操作的输入请求数据。
     * @param content 模型返回的正文内容，与推理文本分开保存。
     * @param tools 当前操作声明的工具定义集合。
     */
    private void validateJson(ChatBO request, String content, boolean tools) {
        if (!Boolean.TRUE.equals(request.json()) || (tools && (content == null || content.isEmpty())))
            return;
        // parser：当前 JSON 响应的流式解析器，用于检查尾部是否还有额外内容。
        try (var parser = json.createParser(content == null ? "" : content)) {
            // 当前解析或检查的 JSON 节点。
            var node = json.readTree(parser);
            if (node == null || !node.isObject() || parser.nextToken() != null)
                throw ModelInvocationException.invalid();
        } catch (java.io.IOException e) {
            // e：完整 JSON 对象解析失败的原因，转为无效上游响应。
            throw ModelInvocationException.invalid();
        }
    }

    /**
     * 从同一个逻辑别名提取模型与供应商配置，生成本次调用的技术快照。
     *
     * @param alias 应用内部的逻辑模型别名，不是供应商原生模型名。
     * @return 供应商客户端所需的连接和模型参数。
     */
    private ProviderModelDTO target(String alias) {
        return ModelProviderConverter.toProvider(alias, registry.model(alias), registry.provider(alias));
    }

    /**
     * 合并各向量批次的真实用量；任何批次整体未知时，整组用量保持未知。
     *
     * @param usages 当前候选各批次供应商实际报告的用量集合。
     * @return 合计用量，或不能确定时的 null。
     */
    private UsageBO sum(List<UsageBO> usages) {
        if (usages.stream().anyMatch(Objects::isNull))
            return null;
        return new UsageBO(
                sumField(usages.stream().map(UsageBO::inputTokens).toList()),
                sumField(usages.stream().map(UsageBO::outputTokens).toList()),
                sumField(usages.stream().map(UsageBO::totalTokens).toList()));
    }

    /**
     * 累计同一个 Token 计数字段，只要有缺失值就保留未知。
     *
     * @param values 各批次同一计数字段的值；包含 null 时合计仍为未知。
     * @return 所有值均已知时的总和，否则为 null。
     */
    private Long sumField(List<Long> values) {
        return values.stream().anyMatch(Objects::isNull) ? null : values.stream().mapToLong(Long::longValue).sum();
    }

    /**
     * 附加本次请求标识和实际逻辑模型，创建与 HTTP 无关的流事件。
     *
     * @param type 流片段或业务事件类型，如 reasoning、content、tool、usage、done、error。
     * @param run 当前逻辑请求共享的预算、可见性与尝试统计状态。
     * @param payload 流事件携带的正文、工具、用量、结束信息或错误信息。
     * @return 可交给 Controller 转为 SSE 的业务事件。
     */
    private ModelEventBO event(String type, Run run, Object payload) {
        return new ModelEventBO(type, run.id, run.model, payload);
    }

    /**
     * 用单调时钟计算总期限剩余量，避免候选降级或分批调用重新开始计时。
     *
     * @param run 当前逻辑请求共享的预算、可见性与尝试统计状态。
     * @return 剩余请求预算；耗尽时直接抛出超时异常。
     */
    private Duration remaining(Run run) {
        // 逻辑请求总期限还剩余的纳秒数。
        long remaining = registry.config().limits().totalTimeout().toNanos() - (System.nanoTime() - run.start);
        if (remaining <= 0)
            throw ModelInvocationException.timeout();
        return Duration.ofNanos(remaining);
    }

    /**
     * 选择两个时限中更短的一个。
     *
     * @param a 第一个待比较的持续时间。
     * @param b 第二个待比较的持续时间。
     * @return 更严格的持续时间限制。
     */
    private Duration min(Duration a, Duration b) {
        return a.compareTo(b) < 0 ? a : b;
    }

    /**
     * 记录一次尝试的分类与耗时，不记录提示词、模型正文和凭据。
     *
     * @param run 当前逻辑请求共享的预算、可见性与尝试统计状态。
     * @param alias 应用内部的逻辑模型别名，不是供应商原生模型名。
     * @param attempt 当前正在记录的实际尝试序号。
     * @param error 当前响应式阶段收到的失败原因，按上下文归类并决定是否允许降级。
     * @param start 当前操作开始时的单调时钟纳秒值。
     * @param signal 当前响应式生命周期信号，用于区分完成、失败和取消。
     */
    private void progress(Run run, String alias, int attempt, ModelInvocationException error, long start, String signal) {
        log.info("ai requestId={} model={} provider={} attempt={} errorCategory={} firstTokenMs={} durationMs={} signal={}",
                run.id, alias, registry.model(alias).provider(), attempt,
                signal.equals("ON_COMPLETE") ? "NONE"
                        : signal.equals("CANCEL") ? "CANCELLED" : error == null ? "UNKNOWN" : error.category(),
                run.firstTokenMs, (System.nanoTime() - start) / 1_000_000, signal);
    }

    /**
     * 释放并发许可等资源，释放异常统一包装为内部状态异常。
     *
     * @param resource 需要在逻辑请求结束或取消时关闭的资源。
     */
    private static void close(AutoCloseable resource) {
        try {
            resource.close();
        } catch (Exception e) {
            // e：关闭资源时出现的异常，包装后继续向上传播。
            throw new IllegalStateException(e);
        }
    }

    /**
     * 一次逻辑请求的运行状态，跨候选共享预算与统计，不跨请求共享。
     */
    private static final class Run {

        /**
         * 贯穿本次逻辑请求、流事件和日志的关联 ID。
         */
        final String id;

        /**
         * 当前操作开始时的单调时钟纳秒值。
         */
        final long start = System.nanoTime();

        /**
         * 当前逻辑请求已经实际发起的候选调用次数。
         */
        int attempts;

        /**
         * 当前尝试所使用的逻辑模型别名，随候选切换而更新。
         */
        String model;

        /**
         * 是否已对调用方产生可见输出；置为 true 后禁止换模型。
         */
        boolean visible;

        /**
         * 逻辑请求到首个有效输出片段的毫秒数；非流式或尚未输出时为 null。
         */
        Long firstTokenMs;

        /**
         * 最近一次候选失败或熔断跳过的错误，候选耗尽时向调用方返回。
         */
        ModelInvocationException last;

        /**
         * 记录逻辑请求标识；起始时间由字段初始化时的单调时钟确定。
         *
         * @param id 贯穿本次逻辑请求、流事件和日志的关联 ID。
         */
        Run(String id) {
            this.id = id;
        }

        /**
         * 计算从逻辑请求开始到当前的毫秒数。
         * @return 累计经过的毫秒数。
         */
        long elapsed() {
            return (System.nanoTime() - start) / 1_000_000;
        }

        /**
         * 生成当前统计快照，覆盖到目前为止的所有实际尝试。
         * @return 尝试次数、总耗时与可选首片段耗时。
         */
        MetricsBO metrics() {
            return new MetricsBO(attempts, elapsed(), firstTokenMs);
        }
    }
}
