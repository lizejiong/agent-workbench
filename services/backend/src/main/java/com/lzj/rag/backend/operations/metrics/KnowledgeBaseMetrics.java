package com.lzj.rag.backend.operations.metrics;

import io.micrometer.core.instrument.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.concurrent.TimeUnit;

/** 定期加载知识库业务指标快照，使指标采集不直接阻塞业务查询。 */
@Component
public class KnowledgeBaseMetrics {

    /** 执行数据库查询及受条件保护的业务状态更新。 */
    private final JdbcTemplate jdbc;

    /** 用于短时状态变更的事务模板。 */
    private final TransactionTemplate read;

    /** 本次模型调用或状态变更所依据的不可变业务快照。 */
    private volatile Map<String, Double> snapshot = Map.of();

    /** 最近一次成功加载指标快照的单调时钟时刻。 */
    private volatile long loadedAt;

    /** 本次清理或执行尝试的开始时间标记。 */
    private volatile long attemptedAt;

    /** 源文件是否可作为文本读取。 */
    private volatile boolean readable;

    /**
     * 初始化当前组件，保存业务依赖及本次使用的配置。
     *
     * @param jdbc 执行数据库查询及受条件保护的业务状态更新。
     * @param manager 创建后台短事务及只读事务的事务管理器。
     * @param registry 记录模型调用及后台任务指标的注册器。
     */
    public KnowledgeBaseMetrics(
            JdbcTemplate jdbc, PlatformTransactionManager manager, MeterRegistry registry) {
        this.jdbc = jdbc;
        read = new TransactionTemplate(manager);
        read.setReadOnly(true);
        read.setTimeout(5);
        read.setIsolationLevel(
                org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        // state：当前刷新状态组件或指标统计的业务状态名称。
        for (String state : List.of("PENDING", "RUNNING", "FAILED", "SUCCEEDED", "CANCELLED")) /*
 * m：已注册的指标快照组件，回调只读取最近采样值。
 */
            Gauge.builder("kb.ingestion.jobs", this, m -> m.value("jobs/" + state))
                    .tag("status", state)
                    .register(registry);
        // kind：当前结构块的类别或业务操作分类。
        for (String kind : List.of("upload", "generation", "document", "remote", "keyword")) /*
 * state：当前刷新状态组件或指标统计的业务状态名称。
 */
            for (String state : List.of("pending", "failed")) /*
 * m：已注册的指标快照组件，回调只读取最近采样值。
 */
                Gauge.builder(
                                "kb.cleanup.resources",
                                this,
                                m -> m.value("cleanup/" + kind + "/" + state))
                        .tags("kind", kind, "status", state)
                        .register(registry);
        // state：当前刷新状态组件或指标统计的业务状态名称。
        for (String state :
                List.of(
                        "PENDING",
                        "RUNNING",
                        "WAITING_INGESTION",
                        "SUCCESS",
                        "FAILED",
                        "SKIPPED")) /*
                                     * m：已注册的指标快照组件，回调只读取最近采样值。
                                     */
            Gauge.builder("kb.refresh.runs", this, m -> m.value("refresh/" + state))
                    .tag("status", state)
                    .register(registry);
        // state：当前刷新状态组件或指标统计的业务状态名称。
        for (String state : List.of("PENDING", "RUNNING", "FAILED", "SUCCEEDED")) /*
 * m：已注册的指标快照组件，回调只读取最近采样值。
 */
            Gauge.builder("kb.keyword.sync", this, m -> m.value("keyword/" + state))
                    .tag("status", state)
                    .register(registry);
        // stage：当前流水线阶段标识。
        for (String stage : List.of("FETCH", "PARSE", "SPLIT", "EMBED", "PERSIST", "PUBLISH")) /*
 * state：当前刷新状态组件或指标统计的业务状态名称。
 */
            for (String state : List.of("SUCCEEDED", "FAILED", "CANCELLED")) /*
 * m：已注册的指标快照组件，回调只读取最近采样值。
 * * m：已注册的指标快照组件，回调只读取最近采样值。
 */
                FunctionTimer.builder(
                                "kb.ingestion.stage",
                                this,
                                m -> m.value("stage/" + stage + "/" + state + "/count").longValue(),
                                m -> m.value("stage/" + stage + "/" + state + "/millis"),
                                TimeUnit.MILLISECONDS)
                        .tags("stage", stage, "status", state)
                        .register(registry);
        // m：已注册的指标快照组件，回调只读取最近采样值。
        Gauge.builder("kb.metrics.snapshot.available", this, m -> m.available()).register(registry);
        // m：已注册的指标快照组件，回调只读取最近采样值。
        Gauge.builder("kb.metrics.snapshot.age", this, m -> m.age())
                .baseUnit("seconds")
                .register(registry);
    }

    /**
     * 读取指定业务指标的最近一次采样值。
     *
     * @param key 对象存储键、配置键或业务幂等键。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private Double value(String key) {
        refresh();
        return snapshot.getOrDefault(key, 0.0);
    }

    /**
     * 返回最近一次指标快照是否成功加载。
     *
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private double available() {
        refresh();
        return readable ? 1 : 0;
    }

    /**
     * 计算最近一次成功采样距今的时长。
     *
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private double age() {
        return loadedAt == 0
                ? Double.NaN
                : TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - loadedAt) / 1000.0;
    }

    /** 在有限事务内刷新业务指标快照，失败时保留已有样本。 */
    public synchronized void refresh() {
        if (attemptedAt != 0 && System.nanoTime() - attemptedAt < TimeUnit.SECONDS.toNanos(10))
            return;
        attemptedAt = System.nanoTime();
        try {
            // loaded：最近一次成功采样的业务指标值集合。
            Map<String, Double> loaded =
                    read.execute(
                            ignored -> {
                                // values：本次处理的参数值或指标快照集合。
                                var values = new HashMap<String, Double>();
                                collect(
                                        values,
                                        "jobs/",
                                        "SELECT status,count(*) AS n FROM kb_ingestion_job GROUP BY status");
                                collect(
                                        values,
                                        "refresh/",
                                        "SELECT status,count(*) AS n FROM kb_refresh_run GROUP BY status");
                                collect(
                                        values,
                                        "keyword/",
                                        "SELECT status,count(*) AS n FROM kb_keyword_sync GROUP BY status");
                                jdbc.query(
                                        "SELECT stage,status,count(*) AS n,COALESCE(sum(duration_ms),0) AS millis FROM kb_ingestion_stage_log WHERE ended_at IS NOT NULL GROUP BY stage,status",
                                        rs -> {
                                            // rs：数据库查询的当前结果行。
                                            // key：对象存储键、配置键或业务幂等键。
                                            String key =
                                                    "stage/"
                                                            + rs.getString("stage")
                                                            + "/"
                                                            + rs.getString("status");
                                            values.put(key + "/count", rs.getDouble("n"));
                                            values.put(key + "/millis", rs.getDouble("millis"));
                                        });
                                jdbc.query(
                                        """
                    SELECT kind,CASE WHEN status='FAILED' THEN 'failed' ELSE 'pending' END AS state,count(*) AS n FROM (
                      SELECT 'upload' AS kind,status FROM kb_upload_operation WHERE status IN ('UPLOADING','CLEANUP_PENDING')
                      UNION ALL SELECT 'generation',status FROM kb_generation_cleanup WHERE status<>'SUCCEEDED'
                      UNION ALL SELECT 'document',status FROM kb_document_cleanup WHERE status<>'SUCCEEDED'
                      UNION ALL SELECT 'remote',status FROM kb_remote_object_cleanup WHERE status NOT IN ('RETAINED','SUCCEEDED')
                      UNION ALL SELECT 'keyword',status FROM kb_keyword_cleanup WHERE status<>'SUCCEEDED'
                    ) resources GROUP BY kind,state
                    """,
                                        rs -> {
                                            // rs：数据库查询的当前结果行。
                                            values.put(
                                                    "cleanup/"
                                                            + rs.getString("kind")
                                                            + "/"
                                                            + rs.getString("state"),
                                                    rs.getDouble("n"));
                                        });
                                return Map.copyOf(values);
                            });
            snapshot = loaded;
            loadedAt = System.nanoTime();
            readable = true;
        } catch (org.springframework.dao.DataAccessException
                | org.springframework.transaction.TransactionException unavailable) {
            // unavailable：指标采集期间捕获的数据库或事务不可用异常。
            readable = false;
            // 采样失败时保留最近成功值，通过样本年龄暴露陈旧程度，日志不包含连接串或底层 SQL 详情。
        }
    }

    /**
     * 执行分组统计并将计数写入指标快照。
     *
     * @param values 本次处理的参数值或指标快照集合。
     * @param prefix 嵌入文本前附加的章节路径或来源提示。
     * @param sql 按当前授权范围构造的 SQL 查询文本。
     */
    private void collect(Map<String, Double> values, String prefix, String sql) {
        jdbc.query(
                sql,
                rs -> {
                    // rs：数据库查询的当前结果行。
                    values.put(prefix + rs.getString("status"), rs.getDouble("n"));
                });
    }
}
