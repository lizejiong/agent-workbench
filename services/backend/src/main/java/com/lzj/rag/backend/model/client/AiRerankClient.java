package com.lzj.rag.backend.model.client;

import com.lzj.rag.backend.knowledgebase.exception.KnowledgeBaseException;
import com.lzj.rag.backend.model.service.RerankClient;
import com.lzj.rag.backend.model.service.bo.RerankInputBO;
import com.lzj.rag.backend.model.service.bo.RerankScoreBO;
import com.lzj.rag.backend.search.config.HybridProperties;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

/** 调用 AI 重排接口并校验文档索引、结果数量和有限分数。 */
@Component
public class AiRerankClient implements RerankClient {

    /** 复用受限 HTTP 传输的 AI 嵌入客户端。 */
    private final AiEmbeddingClient transport;

    /** 混合检索的召回容量、RRF 权重、通道预算和重排配置。 */
    private final HybridProperties config;

    /**
     * 初始化当前组件，保存业务依赖及本次使用的配置。
     *
     * @param transport 复用受限 HTTP 传输的 AI 嵌入客户端。
     * @param config 混合检索的召回容量、RRF 权重、通道预算和重排配置。
     */
    public AiRerankClient(AiEmbeddingClient transport, HybridProperties config) {
        this.transport = transport;
        this.config = config;
    }

    /**
     * 发送文档重排请求，验证返回位置不重复且结果数量与输入一致。
     *
     * @param query 检索查询文本或已生成的查询向量。
     * @param candidates 本次待领取的任务、可选解析策略或召回候选集合。
     * @param requestId 请求跟踪标识或业务幂等键，按当前接口约定使用。
     * @param deadline 本阶段或本次请求必须完成的截止时间。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @Override
    public List<RerankScoreBO> rerank(
            String query, List<RerankInputBO> candidates, String requestId, Instant deadline) {
        // c：当前参与重排的切片输入，包含身份与正文。
        // chars：按 Unicode 码点统计的字符数。
        long chars =
                query.length() + candidates.stream().mapToLong(c -> c.content().length()).sum();
        if (candidates.isEmpty() || chars > config.rerank().maxInputChars())
            throw invalid("KB_RERANK_BUDGET");
        // c：当前参与重排的切片输入，包含身份与正文。
        // result：当前步骤产生的处理结果。
        var result =
                transport.rerankCall(
                        Map.of(
                                "query",
                                query,
                                "documents",
                                candidates.stream()
                                        .map(
                                                c ->
                                                        Map.of(
                                                                "id",
                                                                c.chunkId().toString(),
                                                                "text",
                                                                c.content()))
                                        .toList(),
                                "topN",
                                candidates.size(),
                                "model",
                                config.rerank().model()),
                        requestId,
                        deadline);
        if (!config.rerank().model().equals(result.path("model").asText())
                || !result.path("results").isArray()
                || result.path("results").size() != candidates.size())
            throw invalid("KB_RERANK_INVALID_RESPONSE");
        // scores：模型或通道返回的评分集合。
        var scores = new ArrayList<RerankScoreBO>();
        // seen：用于检测重复输入位置或标识的集合。
        var seen = new HashSet<Integer>();
        // row：当前数据库记录或表格数据行。
        for (var row : result.path("results")) {
            // index：当前元素位置或关键词索引名称。
            var index = row.path("index");
            // score：当前候选的相关性或融合分数。
            var score = row.path("score");
            if (!index.isIntegralNumber()
                    || !index.canConvertToInt()
                    || index.intValue() < 0
                    || index.intValue() >= candidates.size()
                    || !seen.add(index.intValue())
                    || !score.isNumber()
                    || !Double.isFinite(score.doubleValue())
                    || !candidates
                            .get(index.intValue())
                            .chunkId()
                            .toString()
                            .equals(row.path("chunkId").asText()))
                throw invalid("KB_RERANK_INVALID_RESPONSE");
            scores.add(new RerankScoreBO(index.intValue(), score.doubleValue()));
        }
        return scores.stream()
                .sorted(
                        Comparator.comparingDouble(RerankScoreBO::score)
                                .reversed()
                                .thenComparingInt(RerankScoreBO::index))
                .toList();
    }

    /**
     * 创建输入或协议校验失败的业务异常。
     *
     * @param code 对外使用的稳定业务错误分类。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private static KnowledgeBaseException invalid(String code) {
        return new KnowledgeBaseException(
                502, code, "Rerank output or input budget does not match the fixed candidate set");
    }
}
