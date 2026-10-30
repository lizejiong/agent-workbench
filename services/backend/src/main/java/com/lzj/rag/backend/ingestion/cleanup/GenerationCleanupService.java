package com.lzj.rag.backend.ingestion.cleanup;

import com.lzj.rag.backend.ingestion.config.IngestionWorkerProperties;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** 回收已失效代次的数据，失败时按配置记录重试计划。 */
@Service
public class GenerationCleanupService {

    /** 执行数据库查询及受条件保护的业务状态更新。 */
    private final JdbcTemplate jdbc;

    /** 入库工作线程、租约、重试退避和失效代次清理的配置。 */
    private final IngestionWorkerProperties config;

    /**
     * 初始化当前组件，保存业务依赖及本次使用的配置。
     *
     * @param jdbc 执行数据库查询及受条件保护的业务状态更新。
     * @param config 入库工作线程、租约、重试退避和失效代次清理的配置。
     */
    public GenerationCleanupService(JdbcTemplate jdbc, IngestionWorkerProperties config) {
        this.jdbc = jdbc;
        this.config = config;
    }

    /**
     * 列出达到执行时间且仍允许重试的历史代次清理目标。
     *
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public List<UUID> due() {
        return jdbc.queryForList(
                "SELECT id FROM kb_generation_cleanup WHERE status='PENDING' AND next_run_at<=clock_timestamp() ORDER BY next_run_at,id LIMIT 16",
                UUID.class);
    }

    /**
     * 按知识库、文档和任务顺序加锁，确认目标不是有效代次后删除数据。
     *
     * @param id 当前业务对象的唯一标识。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @Transactional(timeout = 15)
    public boolean clean(UUID id) {
        // candidates：本次待领取的任务、可选解析策略或召回候选集合。
        var candidates =
                jdbc.queryForList(
                        "SELECT c.document_id,d.kb_id FROM kb_generation_cleanup c JOIN kb_document d ON d.id=c.document_id WHERE c.id=?",
                        id);
        if (candidates.isEmpty()) return false;
        // c：当前候选、配置项或字符码点。
        var c = candidates.get(0);
        if (jdbc.queryForList(
                        "SELECT id FROM kb_knowledge_base WHERE id=? FOR UPDATE SKIP LOCKED",
                        UUID.class,
                        c.get("kb_id"))
                .isEmpty()) return false;
        // docs：当前范围内的文档记录集合。
        var docs =
                jdbc.queryForList(
                        "SELECT * FROM kb_document WHERE id=? FOR UPDATE SKIP LOCKED",
                        c.get("document_id"));
        if (docs.isEmpty()) return false;
        // rows：数据库结果或当前表格数据行集合。
        var rows =
                jdbc.queryForList(
                        "SELECT * FROM kb_generation_cleanup WHERE id=? AND status='PENDING' AND next_run_at<=clock_timestamp() FOR UPDATE SKIP LOCKED",
                        id);
        if (rows.isEmpty()) return false;
        // row：当前数据库记录或表格数据行。
        var row = rows.get(0);
        // generation：切片或任务所属的数据代次。
        Object generation = row.get("generation");
        if (Objects.equals(generation, docs.get(0).get("active_generation"))
                || Boolean.TRUE.equals(
                        jdbc.queryForObject(
                                "SELECT EXISTS(SELECT 1 FROM kb_ingestion_job WHERE document_id=? AND target_generation=? AND status IN ('PENDING','RUNNING'))",
                                Boolean.class,
                                c.get("document_id"),
                                generation))) {
            jdbc.update(
                    "UPDATE kb_generation_cleanup SET next_run_at=clock_timestamp()+interval '1 minute' WHERE id=?",
                    id);
            return false;
        }
        // 向量记录通过外键随切片级联清理。
        jdbc.update(
                "DELETE FROM kb_chunk WHERE document_id=? AND generation=?",
                c.get("document_id"),
                generation);
        jdbc.update(
                "DELETE FROM kb_generation WHERE document_id=? AND generation=?",
                c.get("document_id"),
                generation);
        jdbc.update(
                "UPDATE kb_generation_cleanup SET status='SUCCEEDED',attempts=attempts+1,completed_at=clock_timestamp(),error_code=NULL WHERE id=?",
                id);
        return true;
    }

    /**
     * 增加代次清理失败次数，并计算下次重试时间或停止重试。
     *
     * @param id 当前业务对象的唯一标识。
     */
    @Transactional(timeout = 10)
    public void failed(UUID id) {
        jdbc.update(
                """
            UPDATE kb_generation_cleanup SET attempts=attempts+1,status=CASE WHEN attempts+1>=? THEN 'FAILED' ELSE 'PENDING' END,
            next_run_at=clock_timestamp()+(?*interval '1 millisecond'),error_code='KB_GENERATION_CLEANUP_FAILED'
            WHERE id=? AND status='PENDING'
            """,
                config.cleanupMaxAttempts(),
                config.maxBackoff().toMillis(),
                id);
    }
}
