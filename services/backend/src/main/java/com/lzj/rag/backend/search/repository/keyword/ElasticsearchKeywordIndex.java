package com.lzj.rag.backend.search.repository.keyword;

import co.elastic.clients.elasticsearch.ElasticsearchAsyncClient;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lzj.rag.backend.knowledgebase.exception.KnowledgeBaseException;
import com.lzj.rag.backend.search.config.HybridProperties;
import com.lzj.rag.backend.search.config.KeywordProperties;
import com.lzj.rag.backend.search.repository.keyword.dto.KeywordChunkDTO;
import com.lzj.rag.backend.search.repository.keyword.dto.KeywordHitDTO;

import jakarta.annotation.PreDestroy;

import org.apache.http.HttpHost;
import org.apache.http.message.BasicHeader;
import org.elasticsearch.client.RestClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.io.StringReader;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

/** 封装关键词索引操作，校验分析器和索引映射，并约束每次调用预算。 */
@Component
@EnableConfigurationProperties({KeywordProperties.class, HybridProperties.class})
public class ElasticsearchKeywordIndex implements KeywordIndex {

    /** 关键词检索开关、索引身份、分析器及同步工作线程配置。 */
    private final KeywordProperties config;

    /** 用于业务请求、持久化快照与元数据转换的 JSON 映射器。 */
    private final ObjectMapper json;

    /** 关键词索引同步客户端。 */
    private final ElasticsearchClient client;

    /** 外部模型或索引请求的底层传输组件。 */
    private final RestClientTransport transport;

    /** 当前索引是否已完成必要的可用性检查。 */
    private volatile boolean checked;

    /** Elasticsearch 异步客户端，用于受预算约束的可取消调用。 */
    private final ElasticsearchAsyncClient async;

    /** 避免并发重复初始化索引的进程内锁。 */
    private final ReentrantLock readiness = new ReentrantLock();

    /**
     * 初始化当前组件，保存业务依赖及本次使用的配置。
     *
     * @param config 关键词检索开关、索引身份、分析器及同步工作线程配置。
     * @param json 用于业务请求、持久化快照与元数据转换的 JSON 映射器。
     */
    public ElasticsearchKeywordIndex(KeywordProperties config, ObjectMapper json) {
        this.config = config;
        this.json = json;
        if (!config.enabled()) {
            client = null;
            transport = null;
            async = null;
            return;
        }
        // timeout：当前阶段允许使用的局部时间预算。
        int timeout = Math.toIntExact(config.callTimeout().toMillis());
        // b：布尔组合查询构建器。
        // b：布尔组合查询构建器。
        // builder：当前组件或请求的参数构建器。
        var builder =
                RestClient.builder(HttpHost.create(config.endpoint()))
                        .setRequestConfigCallback(
                                b ->
                                        b.setConnectTimeout(timeout)
                                                .setConnectionRequestTimeout(timeout)
                                                .setSocketTimeout(timeout))
                        .setHttpClientConfigCallback(
                                b ->
                                        b.setMaxConnTotal(8)
                                                .setMaxConnPerRoute(8)
                                                .disableAuthCaching());
        if (config.apiKey() != null && !config.apiKey().isBlank())
            builder.setDefaultHeaders(
                    new BasicHeader[] {
                        new BasicHeader("Authorization", "ApiKey " + config.apiKey())
                    });
        transport = new RestClientTransport(builder.build(), new JacksonJsonpMapper(json));
        client = new ElasticsearchClient(transport);
        async = new ElasticsearchAsyncClient(transport);
    }

    /** 在有限预算内验证或创建关键词索引，并检查分析器与映射兼容性。 */
    @Override
    public void ready() {
        ready(Instant.now().plus(config.callTimeout().multipliedBy(8)));
    }

    /**
     * 在有限预算内验证或创建关键词索引，并检查分析器与映射兼容性。
     *
     * @param deadline 本阶段或本次请求必须完成的截止时间。
     */
    private void ready(Instant deadline) {
        if (checked) return;
        if (client == null) throw unavailable();
        // acquired：是否已经取得本次操作所需并发许可。
        boolean acquired = false;
        try {
            deadline(deadline);
            acquired =
                    readiness.tryLock(
                            Math.max(1, Duration.between(Instant.now(), deadline).toNanos()),
                            TimeUnit.NANOSECONDS);
            if (!acquired) throw timedOut();
            deadline(deadline);
            if (checked) return;
            // version：当前记录版本或本次请求固定的版本标记。
            var version = await(async.info(), deadline).version().number();
            // parts：按分隔符拆分的文本片段。
            String[] parts = version.split("\\.");
            if (parts.length < 2 || !parts[0].equals("8") || Integer.parseInt(parts[1]) < 18)
                throw new KnowledgeBaseException(
                        503,
                        "KB_KEYWORD_VERSION",
                        "Use the tested Elasticsearch 8.18+ protocol family; server validation required");
            // a：当前合并候选或地址数值。
            // tokens：按文本长度估算得到的 Token 数量。
            var tokens =
                    await(
                                    async.indices()
                                            .analyze(
                                                    a ->
                                                            a.analyzer(config.analyzer())
                                                                    .text("中华人民共和国知识库 ZX1472")),
                                    deadline)
                            .tokens();
            // c：当前处理的 Unicode 码点或 SAX 字符片段。
            // t：精确词项或词项集合查询构建器。
            if (tokens.isEmpty()
                    || tokens.stream()
                            .noneMatch(
                                    t ->
                                            t.token().codePointCount(0, t.token().length()) > 1
                                                    && t.token()
                                                            .codePoints()
                                                            .anyMatch(c -> c > 127)))
                throw new KnowledgeBaseException(
                        503,
                        "KB_KEYWORD_ANALYZER",
                        "Configured Chinese analyzer did not produce supported tokens");
            // e：当前证据或配置键值项。
            if (!await(async.indices().exists(e -> e.index(config.index())), deadline).value()) {
                // fields：当前 JSON 对象或模型响应中的字段集合。
                var fields = new LinkedHashMap<String, Object>();
                // name：当前业务资源或策略的公开名称。
                for (String name :
                        List.of(
                                "id",
                                "chunkId",
                                "kbId",
                                "documentId",
                                "tenantId",
                                "ownerId",
                                "versionKey",
                                "contentHash")) fields.put(name, Map.of("type", "keyword"));
                // name：当前业务资源或策略的公开名称。
                for (String name : List.of("generation", "syncRevision", "chunkIndex"))
                    fields.put(name, Map.of("type", "long"));
                // name：当前业务资源或策略的公开名称。
                for (String name : List.of("enabled", "chunkEnabled", "documentEnabled"))
                    fields.put(name, Map.of("type", "boolean"));
                fields.put("documentName", Map.of("type", "keyword", "index", false));
                fields.put(
                        "content",
                        Map.of(
                                "type",
                                "text",
                                "analyzer",
                                config.analyzer(),
                                "similarity",
                                "BM25"));
                fields.put("source", Map.of("type", "object", "enabled", false));
                // mapping：关键词索引映射定义或实际映射结果。
                String mapping =
                        encode(
                                Map.of(
                                        "settings",
                                        Map.of("number_of_shards", 1, "number_of_replicas", 0),
                                        "mappings",
                                        Map.of(
                                                "dynamic",
                                                "strict",
                                                "_meta",
                                                Map.of(
                                                        "kb_schema",
                                                        "1",
                                                        "kb_analyzer",
                                                        config.analyzer()),
                                                "properties",
                                                fields)));
                try {
                    // c：当前候选、配置项或字符码点。
                    await(
                            async.indices()
                                    .create(
                                            c ->
                                                    c.index(config.index())
                                                            .withJson(new StringReader(mapping))),
                            deadline);
                } catch (co.elastic.clients.elasticsearch._types.ElasticsearchException race) {
                    // e：当前证据或配置键值项。
                    // race：并发创建索引时捕获的 Elasticsearch 状态异常。
                    if (!await(async.indices().exists(e -> e.index(config.index())), deadline)
                            .value()) throw race;
                }
            }
            // g：索引查询参数构建器。
            // mapping：关键词索引映射定义或实际映射结果。
            var mapping =
                    await(async.indices().getMapping(g -> g.index(config.index())), deadline)
                            .result()
                            .get(config.index())
                            .mappings();
            if (!"1"
                            .equals(
                                    mapping.meta()
                                            .getOrDefault(
                                                    "kb_schema",
                                                    co.elastic.clients.json.JsonData.of(""))
                                            .to(String.class))
                    || !config.analyzer()
                            .equals(mapping.properties().get("content").text().analyzer())
                    || !"BM25".equals(mapping.properties().get("content").text().similarity()))
                throw new KnowledgeBaseException(
                        503,
                        "KB_KEYWORD_MAPPING",
                        "Create a new dedicated KB index with the expected schema/analyzer; do not reuse an unrelated index");
            checked = true;
        } catch (InterruptedException e) {
            // e：本次捕获或匹配的异常，用于保留业务分类及失败原因。
            Thread.currentThread().interrupt();
            throw timedOut();
        } catch (KnowledgeBaseException e) {
            // e：本次捕获或匹配的异常，用于保留业务分类及失败原因。
            throw e;
        } catch (Exception e) {
            // e：本次捕获或匹配的异常，用于保留业务分类及失败原因。
            throw unavailable();
        } finally {
            if (acquired) readiness.unlock();
        }
    }

    /**
     * 批量写入带版本的关键词切片，并检查每项写入结果。
     *
     * @param chunks 本次处理的有序切片集合。
     * @param deadline 本阶段或本次请求必须完成的截止时间。
     */
    @Override
    public void write(List<KeywordChunkDTO> chunks, Instant deadline) {
        ready(deadline);
        try {
            // start：当前片段或表格分组的起始位置。
            for (int start = 0; start < chunks.size(); start += config.batchSize()) {
                deadline(deadline);
                // builder：当前组件或请求的参数构建器。
                var builder =
                        new BulkRequest.Builder().index(config.index()).refresh(Refresh.WaitFor);
                // row：当前数据库记录或表格数据行。
                for (var row :
                        chunks.subList(
                                start, Math.min(chunks.size(), start + config.batchSize()))) /*
 * i：当前遍历位置，按原始输入顺序递增。
 * * o：当前索引请求操作构建器。
 */
                    builder.operations(o -> o.index(i -> i.id(row.id()).document(row)));
                if (client.bulk(builder.build()).errors()) throw unavailable();
            }
        } catch (KnowledgeBaseException e) {
            // e：本次捕获或匹配的异常，用于保留业务分类及失败原因。
            throw e;
        } catch (Exception e) {
            // e：本次捕获或匹配的异常，用于保留业务分类及失败原因。
            throw unavailable();
        }
    }

    /**
     * 在租户、所有者和有效版本范围内执行 BM25 查询。
     *
     * @param query 检索查询文本或已生成的查询向量。
     * @param tenant 数据所属租户。
     * @param owner 数据所有者或当前租约持有实例的标识。
     * @param keys 本次查询或索引操作使用的键集合。
     * @param limit 当前查询数量、下载字节数或解析字符预算的上限。
     * @param deadline 本阶段或本次请求必须完成的截止时间。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @Override
    public List<KeywordHitDTO> query(
            String query,
            String tenant,
            String owner,
            List<String> keys,
            int limit,
            Instant deadline) {
        deadline(deadline);
        if (keys.isEmpty()) return List.of();
        ready(deadline);
        deadline(deadline);
        try {
            // remaining：当前操作剩余的时间或容量预算。
            long remaining =
                    Math.max(
                            1,
                            Math.min(
                                    config.callTimeout().toMillis(),
                                    Duration.between(Instant.now(), deadline).toMillis()));
            // t：精确词项或词项集合查询构建器。
            // f：索引字段或过滤条件构建器。
            // v：当前词项集合值构建器。
            // sc：索引响应或排序配置构建器。
            // v：当前词项集合值构建器。
            // t：精确词项或词项集合查询构建器。
            // m：索引映射或全文匹配构建器。
            // v：当前词项集合值构建器。
            // t：精确词项或词项集合查询构建器。
            // f：索引字段或过滤条件构建器。
            // t：精确词项或词项集合查询构建器。
            // f：索引字段或过滤条件构建器。
            // t：精确词项或词项集合查询构建器。
            // f：索引字段或过滤条件构建器。
            // t：精确词项或词项集合查询构建器。
            // f：索引字段或过滤条件构建器。
            // b：布尔组合查询构建器。
            // q：索引查询构建器。
            // f：索引字段或过滤条件构建器。
            // source：当前结构块的来源字段或已校验临时源文件。
            // s：索引结构、排序或搜索请求构建器。
            // result：当前步骤产生的处理结果。
            var result =
                    await(
                            async.search(
                                    s ->
                                            s.index(config.index())
                                                    .size(limit)
                                                    .timeout(remaining + "ms")
                                                    .source(
                                                            source ->
                                                                    source.filter(
                                                                            f ->
                                                                                    f.includes(
                                                                                            "chunkId",
                                                                                            "syncRevision",
                                                                                            "contentHash")))
                                                    .query(
                                                            q ->
                                                                    q.bool(
                                                                            b ->
                                                                                    b.filter(
                                                                                                    f ->
                                                                                                            f
                                                                                                                    .term(
                                                                                                                            t ->
                                                                                                                                    t.field(
                                                                                                                                                    "tenantId")
                                                                                                                                            .value(
                                                                                                                                                    tenant)))
                                                                                            .filter(
                                                                                                    f ->
                                                                                                            f
                                                                                                                    .term(
                                                                                                                            t ->
                                                                                                                                    t.field(
                                                                                                                                                    "ownerId")
                                                                                                                                            .value(
                                                                                                                                                    owner)))
                                                                                            .filter(
                                                                                                    f ->
                                                                                                            f
                                                                                                                    .term(
                                                                                                                            t ->
                                                                                                                                    t.field(
                                                                                                                                                    "enabled")
                                                                                                                                            .value(
                                                                                                                                                    true)))
                                                                                            .filter(
                                                                                                    f ->
                                                                                                            f
                                                                                                                    .terms(
                                                                                                                            t ->
                                                                                                                                    t.field(
                                                                                                                                                    "versionKey")
                                                                                                                                            .terms(
                                                                                                                                                    v ->
                                                                                                                                                            v
                                                                                                                                                                    .value(
                                                                                                                                                                            keys
                                                                                                                                                                                    .stream()
                                                                                                                                                                                    .map(
                                                                                                                                                                                            FieldValue
                                                                                                                                                                                                    ::of)
                                                                                                                                                                                    .toList()))))
                                                                                            .must(
                                                                                                    m ->
                                                                                                            m
                                                                                                                    .match(
                                                                                                                            t ->
                                                                                                                                    t.field(
                                                                                                                                                    "content")
                                                                                                                                            .query(
                                                                                                                                                    query)))))
                                                    .sort(
                                                            v ->
                                                                    v.score(
                                                                            sc ->
                                                                                    sc.order(
                                                                                            SortOrder
                                                                                                    .Desc)))
                                                    .sort(
                                                            v ->
                                                                    v.field(
                                                                            f ->
                                                                                    f.field("id")
                                                                                            .order(
                                                                                                    SortOrder
                                                                                                            .Asc)))
                                                    .trackTotalHits(t -> t.enabled(false)),
                                    Map.class),
                            deadline);
            if (result.timedOut() || result.shards().failed().longValue() > 0) throw unavailable();
            // hits：当前查询返回的召回命中集合。
            var hits = new ArrayList<KeywordHitDTO>();
            // rank：候选在当前通道中的排名，从一开始计数。
            int rank = 0;
            // hit：当前向量或关键词召回命中。
            for (var hit : result.hits().hits()) {
                rank++;
                // source：当前结构块的来源字段或已校验临时源文件。
                var source = hit.source();
                if (source == null || hit.score() == null || !Double.isFinite(hit.score()))
                    throw unavailable();
                hits.add(
                        new KeywordHitDTO(
                                UUID.fromString(source.get("chunkId").toString()),
                                ((Number) source.get("syncRevision")).longValue(),
                                source.get("contentHash").toString(),
                                rank,
                                hit.score()));
            }
            return List.copyOf(hits);
        } catch (KnowledgeBaseException e) {
            // e：本次捕获或匹配的异常，用于保留业务分类及失败原因。
            throw e;
        } catch (Exception e) {
            // failure：当前需要传播或记录的业务失败。
            // e：本次捕获或匹配的异常，用于保留业务分类及失败原因。
            var failure = unavailable();
            failure.initCause(e);
            throw failure;
        }
    }

    /**
     * 删除目标知识库中早于指定同步修订的历史索引文档。
     *
     * @param document 本次操作涉及的文档唯一标识。
     * @param revision 当前同步数据或配置使用的修订标识。
     * @param deadline 本阶段或本次请求必须完成的截止时间。
     */
    @Override
    public void deleteBefore(UUID document, long revision, Instant deadline) {
        deadline(deadline);
        ready(deadline);
        deadline(deadline);
        try {
            // u：未指定字段类型的范围查询构建器。
            // r：范围查询或批量操作构建器。
            // f：索引字段或过滤条件构建器。
            // t：精确词项或词项集合查询构建器。
            // f：索引字段或过滤条件构建器。
            // b：布尔组合查询构建器。
            // q：索引查询构建器。
            // d：当前文档记录。
            // result：当前步骤产生的处理结果。
            var result =
                    client.deleteByQuery(
                            d ->
                                    d.index(config.index())
                                            .refresh(true)
                                            .waitForCompletion(true)
                                            .conflicts(
                                                    co.elastic.clients.elasticsearch._types
                                                            .Conflicts.Proceed)
                                            .query(
                                                    q ->
                                                            q.bool(
                                                                    b ->
                                                                            b.filter(
                                                                                            f ->
                                                                                                    f
                                                                                                            .term(
                                                                                                                    t ->
                                                                                                                            t.field(
                                                                                                                                            "documentId")
                                                                                                                                    .value(
                                                                                                                                            document
                                                                                                                                                    .toString())))
                                                                                    .filter(
                                                                                            f ->
                                                                                                    f
                                                                                                            .range(
                                                                                                                    r ->
                                                                                                                            r
                                                                                                                                    .untyped(
                                                                                                                                            u ->
                                                                                                                                                    u.field(
                                                                                                                                                                    "syncRevision")
                                                                                                                                                            .lt(
                                                                                                                                                                    co
                                                                                                                                                                            .elastic
                                                                                                                                                                            .clients
                                                                                                                                                                            .json
                                                                                                                                                                            .JsonData
                                                                                                                                                                            .of(
                                                                                                                                                                                    revision))))))));
            if (result.timedOut() || !result.failures().isEmpty() || result.versionConflicts() != 0)
                throw unavailable();
        } catch (KnowledgeBaseException e) {
            // e：本次捕获或匹配的异常，用于保留业务分类及失败原因。
            throw e;
        } catch (Exception e) {
            // e：本次捕获或匹配的异常，用于保留业务分类及失败原因。
            throw unavailable();
        }
    }

    /** 释放当前对象持有的临时文件或执行资源。 */
    @PreDestroy
    void close() throws Exception {
        if (transport != null) transport.close();
    }

    /**
     * 按剩余预算等待异步索引请求，超时或中断时取消底层调用。
     *
     * @param future 当前异步操作的可等待结果，超时或失去执行权时用于取消任务。
     * @param deadline 本阶段或本次请求必须完成的截止时间。
     * @param <T> 当前业务结果或输入元素的类型。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private <T> T await(CompletableFuture<T> future, Instant deadline) throws Exception {
        try {
            deadline(deadline);
            // result：当前步骤产生的处理结果。
            T result =
                    future.get(
                            Math.max(
                                    1,
                                    Math.min(
                                            config.callTimeout().toNanos(),
                                            Duration.between(Instant.now(), deadline).toNanos())),
                            TimeUnit.NANOSECONDS);
            deadline(deadline);
            return result;
        } catch (TimeoutException e) {
            // e：本次捕获或匹配的异常，用于保留业务分类及失败原因。
            throw timedOut();
        } catch (InterruptedException e) {
            // e：本次捕获或匹配的异常，用于保留业务分类及失败原因。
            Thread.currentThread().interrupt();
            throw timedOut();
        } catch (ExecutionException e) {
            // cause：异步或底层调用失败的原始异常。
            // e：本次捕获或匹配的异常，用于保留业务分类及失败原因。
            if (e.getCause() instanceof Exception cause) throw cause;
            throw unavailable();
        } finally {
            if (!future.isDone()) future.cancel(true);
        }
    }

    /**
     * 将业务对象序列化为 JSON，序列化失败时保留明确异常。
     *
     * @param value 当前待校验、转换或写入的值。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            // e：本次捕获或匹配的异常，用于保留业务分类及失败原因。
            throw new IllegalStateException(e);
        }
    }

    /**
     * 检查调用线程中断及索引操作截止时间。
     *
     * @param deadline 本阶段或本次请求必须完成的截止时间。
     */
    private static void deadline(Instant deadline) {
        if (Thread.currentThread().isInterrupted() || !Instant.now().isBefore(deadline))
            throw new KnowledgeBaseException(
                    504, "KB_KEYWORD_TIMEOUT", "Keyword channel time budget expired");
    }

    /**
     * 创建外部索引调用超时异常。
     *
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private static KnowledgeBaseException timedOut() {
        return new KnowledgeBaseException(
                504, "KB_KEYWORD_TIMEOUT", "Keyword channel time budget expired");
    }

    /**
     * 创建依赖暂不可用的业务异常。
     *
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private static KnowledgeBaseException unavailable() {
        return new KnowledgeBaseException(
                503,
                "KB_KEYWORD_UNAVAILABLE",
                "Keyword service or schema is unavailable; inspect sync status, no source/credential details are exposed");
    }
}
