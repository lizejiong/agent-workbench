package com.lzj.rag.backend.search.service.impl;

import com.fasterxml.jackson.databind.*;
import com.lzj.rag.backend.document.service.internal.DocumentAccess;
import com.lzj.rag.backend.knowledgebase.dao.entity.KnowledgeBaseDO;
import com.lzj.rag.backend.knowledgebase.exception.KnowledgeBaseException;
import com.lzj.rag.backend.knowledgebase.service.internal.KnowledgeBaseAccess;
import com.lzj.rag.backend.model.service.EmbeddingClient;
import com.lzj.rag.backend.model.service.bo.EmbeddingBindingBO;
import com.lzj.rag.backend.search.budget.RetrievalDeadline;
import com.lzj.rag.backend.search.config.SearchProperties;
import com.lzj.rag.backend.search.repository.vector.VectorStore;
import com.lzj.rag.backend.search.repository.vector.dto.VectorScopeDTO;
import com.lzj.rag.backend.search.service.KnowledgeSearchService;
import com.lzj.rag.backend.search.service.bo.EvidenceBO;
import com.lzj.rag.backend.search.service.bo.SearchBO;
import com.lzj.rag.backend.security.model.KnowledgeBasePrincipal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/** 校验检索范围和向量空间兼容性，嵌入查询并召回当前有效切片。 */
@Service
@EnableConfigurationProperties(SearchProperties.class)
public class KnowledgeSearchServiceImpl implements KnowledgeSearchService {

    /** 供内部流程校验知识库归属和取得行锁的访问契约。 */
    private final KnowledgeBaseAccess bases;

    /** 供内部流程校验文档归属和取得行锁的访问契约。 */
    private final DocumentAccess documents;

    /** 受固定模型身份约束的嵌入客户端。 */
    private final EmbeddingClient embeddings;

    /** 在固定所有权及命名空间内读写切片向量的存储组件。 */
    private final VectorStore vectors;

    /** 用于业务请求、持久化快照与元数据转换的 JSON 映射器。 */
    private final ObjectMapper json;

    /** 检索范围、查询长度、返回数量和总时间预算的限制。 */
    private final SearchProperties config;

    /** 允许公开的来源元数据字段集合。 */
    private static final Set<String> SOURCE_FIELDS =
            Set.of(
                    "chapterPath",
                    "parserVersion",
                    "blockType",
                    "pageNumber",
                    "pageStart",
                    "pageEnd",
                    "rowStart",
                    "rowEnd",
                    "columnStart",
                    "columnEnd",
                    "fragmentLineStart",
                    "fragmentLineEnd",
                    "lineStart",
                    "lineEnd",
                    "textStart",
                    "textEnd",
                    "offsetUnit",
                    "headerRepeated",
                    "wholeDocument",
                    "overlapChars",
                    "manual",
                    "originalFileContainsContent",
                    "sheetName",
                    "tableName",
                    "rowNumbers",
                    "headerRow",
                    "rowNumberUnit",
                    "formulaPolicy");

    /** 允许公开的结构块定位字段集合。 */
    private static final Set<String> BLOCK_FIELDS =
            Set.of(
                    "blockIndex",
                    "lineStart",
                    "lineEnd",
                    "pageNumber",
                    "pageStart",
                    "pageEnd",
                    "sheetName",
                    "rowStart",
                    "rowEnd",
                    "columnStart",
                    "columnEnd",
                    "language",
                    "slideNumber",
                    "bbox",
                    "provenance",
                    "generated",
                    "imageNumber",
                    "imageWidth",
                    "imageHeight",
                    "vlmModel",
                    "headerRow",
                    "formulaPolicy",
                    "rowNumberUnit",
                    "tableName",
                    "headerPolicy");

    /**
     * 初始化当前组件，保存业务依赖及本次使用的配置。
     *
     * @param bases 供内部流程校验知识库归属和取得行锁的访问契约。
     * @param documents 供内部流程校验文档归属和取得行锁的访问契约。
     * @param embeddings 受固定模型身份约束的嵌入客户端。
     * @param vectors 在固定所有权及命名空间内读写切片向量的存储组件。
     * @param json 用于业务请求、持久化快照与元数据转换的 JSON 映射器。
     * @param config 检索范围、查询长度、返回数量和总时间预算的限制。
     */
    public KnowledgeSearchServiceImpl(
            KnowledgeBaseAccess bases,
            DocumentAccess documents,
            EmbeddingClient embeddings,
            VectorStore vectors,
            ObjectMapper json,
            SearchProperties config) {
        this.bases = bases;
        this.documents = documents;
        this.embeddings = embeddings;
        this.vectors = vectors;
        this.json = json;
        this.config = config;
    }

    /**
     * 执行受统一时间预算约束的向量检索。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param request 当前入口接收的请求参数。
     * @param requestId 请求跟踪标识或业务幂等键，按当前接口约定使用。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public List<EvidenceBO> search(
            KnowledgeBasePrincipal actor, SearchBO request, String requestId) {
        return recall(actor, request, requestId, request.topK());
    }

    /**
     * 校验范围与嵌入绑定兼容性，计算查询向量并加载有效切片证据。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param request 当前入口接收的请求参数。
     * @param requestId 请求跟踪标识或业务幂等键，按当前接口约定使用。
     * @param limit 当前查询数量、下载字节数或解析字符预算的上限。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public List<EvidenceBO> recall(
            KnowledgeBasePrincipal actor, SearchBO request, String requestId, int limit) {
        return recall(actor, request, requestId, limit, Instant.now().plus(config.deadline()));
    }

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
    public List<EvidenceBO> recall(
            KnowledgeBasePrincipal actor,
            SearchBO request,
            String requestId,
            int limit,
            Instant deadline) {
        RetrievalDeadline.check(deadline);
        // query：检索查询文本或已生成的查询向量。
        String query = request.query() == null ? "" : request.query().strip();
        // c：当前处理的 Unicode 码点或 SAX 字符片段。
        if (query.isBlank()
                || query.codePointCount(0, query.length()) > config.maxQueryChars()
                || query.codePoints().anyMatch(c -> c == 0 || (c >= 0xd800 && c <= 0xdfff))
                || request.topK() == null
                || request.topK() < 1
                || request.topK() > config.maxTopK()
                || !request.isMinScoreValid())
            throw invalid("Check query, topK and finite minScore limits");
        // kbIds：本次检索或查询涉及的知识库标识集合。
        List<UUID> kbIds = ids(request.kbIds(), config.maxKbIds(), false);
        // docIds：限定本次查询的文档标识集合。
        List<UUID> docIds = ids(request.documentIds(), config.maxDocumentIds(), true);
        // selected：当前选定的切片、表格行或业务记录集合。
        // 选择模型或调用外部服务前校验全部请求资源，不静默截短查询范围。
        var selected = new ArrayList<KnowledgeBaseDO>();
        // id：当前业务对象的唯一标识。
        for (UUID id : kbIds) selected.add(bases.requireOwned(actor, id));
        // kbSet：用于快速校验文档归属的知识库标识集合。
        var kbSet = new HashSet<>(kbIds);
        // id：当前业务对象的唯一标识。
        for (UUID id : docIds)
            if (!kbSet.contains(documents.requireOwned(actor, id).getKbId()))
                throw new KnowledgeBaseException(
                        400,
                        "KB_SEARCH_DOCUMENT_SCOPE",
                        "Every document must belong to one of the selected knowledge bases");
        // groups：按结构边界分组的内容集合。
        var groups = new LinkedHashMap<EmbeddingBindingBO, List<KnowledgeBaseDO>>();
        // revisions：当前切片对应的可见关键词修订集合。
        var revisions = new HashSet<String>();
        // base：当前操作涉及的知识库及其固定模型绑定。
        for (var base : selected) {
            if (base.getEmbeddingModelId() == null)
                throw new KnowledgeBaseException(
                        409,
                        "KB_MODEL_UNBOUND",
                        "Bind the selected knowledge base before searching");
            try {
                // model：当前固定的模型配置或 AI 返回的模型身份节点。
                var model = json.readTree(base.getEmbeddingConfigJson());
                // binding：本次业务固定使用的嵌入模型身份与配置快照。
                // AI 修订尚未固定的空库可以与本地固定模型身份相同的已有内容知识库共同检索。
                var binding =
                        new EmbeddingBindingBO(
                                base.getEmbeddingModelId(),
                                base.getEmbeddingProvider(),
                                model.path("modelName").asText(),
                                base.getEmbeddingDimension(),
                                model.path("embeddingSpace").asText(),
                                model.path("configVersion").asText(),
                                base.getEmbeddingConfigRevision());
                // key：对象存储键、配置键或业务幂等键。
                groups.computeIfAbsent(binding, key -> new ArrayList<>()).add(base);
                if (base.getEmbeddingAiConfigRevision() != null)
                    revisions.add(base.getEmbeddingAiConfigRevision());
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                // e：本次捕获或匹配的异常，用于保留业务分类及失败原因。
                throw new IllegalStateException("Invalid persisted model binding", e);
            }
        }
        if (groups.size() != 1 || revisions.size() > 1)
            throw new KnowledgeBaseException(
                    409,
                    "KB_SEARCH_MODEL_MISMATCH",
                    "Search one embedding model and configuration revision per request; query incompatible knowledge bases separately");
        // 尚无已发布向量固定 AI 修订，直接返回空证据，无需调用模型。
        if (revisions.isEmpty()) return List.of();
        // identity：本次响应中需要校验的模型或配置身份。
        var identity = groups.keySet().iterator().next();
        // revision：当前同步数据或配置使用的修订标识。
        String revision = revisions.iterator().next();
        // binding：本次业务固定使用的嵌入模型身份与配置快照。
        var binding =
                new EmbeddingBindingBO(
                        identity.modelId(),
                        identity.provider(),
                        identity.modelName(),
                        identity.dimension(),
                        identity.embeddingSpace(),
                        identity.configVersion(),
                        identity.localRevision(),
                        revision);
        RetrievalDeadline.check(deadline);
        // result：当前步骤产生的处理结果。
        var result = embeddings.embed(binding, List.of(query), requestId, deadline);
        if (result == null
                || result.vectors() == null
                || result.vectors().size() != 1
                || !revision.equals(result.aiConfigRevision()))
            throw new KnowledgeBaseException(
                    502,
                    "KB_MODEL_INVALID_RESPONSE",
                    "Query embedding does not match the bound index");
        // base：当前操作涉及的知识库及其固定模型绑定。
        // scopes：本次向量检索允许访问的范围集合。
        var scopes =
                selected.stream()
                        .map(
                                base ->
                                        new VectorScopeDTO(
                                                actor,
                                                base.getId(),
                                                base.getVectorNamespace(),
                                                base.getEmbeddingModelId(),
                                                base.getEmbeddingConfigRevision()))
                        .toList();
        RetrievalDeadline.check(deadline);
        // hits：当前查询返回的召回命中集合。
        var hits =
                vectors.query(
                        scopes,
                        result.vectors().get(0),
                        revision,
                        docIds,
                        limit,
                        request.minScore());
        RetrievalDeadline.check(deadline);
        return hits.stream()
                .map(
                        hit -> {
                            // hit：当前向量或关键词召回命中。
                            // score：当前候选的相关性或融合分数。
                            double score = 1 - hit.cosineDistance();
                            if (!Double.isFinite(score))
                                throw new KnowledgeBaseException(
                                        500,
                                        "KB_INDEX_INVALID",
                                        "Index returned an invalid cosine distance");
                            return new EvidenceBO(
                                    hit.chunkId(),
                                    hit.kbId(),
                                    hit.documentId(),
                                    hit.documentName(),
                                    hit.chunkIndex(),
                                    hit.generation(),
                                    hit.content(),
                                    Math.max(-1, Math.min(1, score)),
                                    "COSINE_SIMILARITY",
                                    source(hit.metadataJson()),
                                    "/api/documents/" + hit.documentId() + "/file");
                        })
                .toList();
    }

    /**
     * 校验知识库或文档标识集合的数量、唯一性和必填约束。
     *
     * @param values 本次处理的参数值或指标快照集合。
     * @param maximum 当前业务校验允许的数量或长度上限。
     * @param optional 当前范围参数是否允许省略。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private List<UUID> ids(List<UUID> values, int maximum, boolean optional) {
        if (values == null && optional) return List.of();
        if (values == null
                || values.isEmpty()
                || values.size() > maximum
                || values.stream().anyMatch(Objects::isNull)
                || new HashSet<>(values).size() != values.size())
            throw invalid(
                    "Resource ids must be nonempty, unique, valid and within configured count limits");
        return values.stream().sorted().toList();
    }

    /**
     * 校验并清理来源元数据，仅保留允许公开的定位字段。
     *
     * @param raw 尚未校验或规范化的原始输入。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public Map<String, Object> source(String raw) {
        try {
            // root：JSON 响应根节点。
            var root = json.readTree(raw);
            if (root == null || !root.isObject())
                throw new IllegalStateException("Invalid source metadata");
            // output：本阶段产生的输出对象。
            var output = new LinkedHashMap<String, Object>();
            root.fields()
                    .forEachRemaining(
                            field -> {
                                // field：当前需要读取或过滤的字段名。
                                if (SOURCE_FIELDS.contains(field.getKey()))
                                    output.put(field.getKey(), field.getValue());
                            });
            if (root.path("sourceBlocks").isArray()) {
                // blocks：按源文档顺序排列的结构块。
                var blocks = new ArrayList<Map<String, Object>>();
                // block：当前处理的结构块。
                for (var block : root.path("sourceBlocks")) {
                    // safe：已经按字段许可集合过滤的来源元数据。
                    var safe = new LinkedHashMap<String, Object>();
                    block.fields()
                            .forEachRemaining(
                                    field -> {
                                        // field：当前需要读取或过滤的字段名。
                                        if (BLOCK_FIELDS.contains(field.getKey()))
                                            safe.put(field.getKey(), field.getValue());
                                    });
                    blocks.add(safe);
                }
                output.put("sourceBlocks", blocks);
            }
            return output;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // e：本次捕获或匹配的异常，用于保留业务分类及失败原因。
            throw new IllegalStateException("Invalid persisted source metadata", e);
        }
    }

    /**
     * 创建输入或协议校验失败的业务异常。
     *
     * @param message 对外展示的业务错误或状态说明。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private static KnowledgeBaseException invalid(String message) {
        return new KnowledgeBaseException(400, "KB_INVALID_INPUT", message);
    }
}
