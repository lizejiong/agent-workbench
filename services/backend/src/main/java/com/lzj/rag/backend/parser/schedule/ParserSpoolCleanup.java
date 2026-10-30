package com.lzj.rag.backend.parser.schedule;

import com.lzj.rag.backend.infra.storage.config.StorageProperties;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.*;
import java.time.*;
import java.util.UUID;

/** 清理超过保留时间且不再受有效入库租约保护的解析临时文件。 */
@Component
public class ParserSpoolCleanup {

    /** 对象存储连接、文件容量、临时目录及上传补偿配置。 */
    private final StorageProperties config;

    /** 执行数据库查询及受条件保护的业务状态更新。 */
    private final JdbcTemplate jdbc;

    /**
     * 初始化当前组件，保存业务依赖及本次使用的配置。
     *
     * @param config 对象存储连接、文件容量、临时目录及上传补偿配置。
     * @param jdbc 执行数据库查询及受条件保护的业务状态更新。
     */
    public ParserSpoolCleanup(StorageProperties config, JdbcTemplate jdbc) {
        this.config = config;
        this.jdbc = jdbc;
    }

    /** 扫描允许回收的临时文件，避开仍由有效任务租约使用的文件。 */
    @Scheduled(
            fixedDelayString = "${kb.formats.spool-sweep-delay:300000}",
            initialDelayString = "${kb.formats.spool-sweep-delay:300000}")
    public void scan() {
        // directory：临时文件所在目录。
        var directory = config.tempDirectory();
        if (directory == null || directory.toString().isBlank())
            directory = Path.of(System.getProperty("java.io.tmpdir"));
        if (!Files.isDirectory(directory)) return;
        // paths：待处理的文件路径集合。
        try (var paths = Files.list(directory)) {
            // p：当前扫描的临时文件路径，用于筛选允许清理的文件。
            // p：当前扫描的临时文件路径，用于筛选允许清理的文件。
            // file：待校验、读取或写入的源文件。
            for (var file :
                    paths.filter(
                                    p ->
                                            p.getFileName().toString().startsWith("kb-source-")
                                                    || p.getFileName()
                                                            .toString()
                                                            .startsWith("kb-parser-request-")
                                                    || p.getFileName()
                                                            .toString()
                                                            .startsWith("kb-remote-")
                                                    || p.getFileName()
                                                            .toString()
                                                            .startsWith("kb-upload-"))
                            .filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                            .filter(this::oldEnough)
                            .limit(256)
                            .toList()) {
                // name：当前业务资源或策略的公开名称。
                String name = file.getFileName().toString();
                if (name.startsWith("kb-source-")) {
                    if (name.length() < 46) continue;
                    // job：当前入库任务记录或任务标识。
                    UUID job;
                    try {
                        job = UUID.fromString(name.substring(10, 46));
                    } catch (IllegalArgumentException invalid) {
                        // invalid：校验配置或输入时捕获的非法参数异常。
                        continue;
                    }
                    if (Boolean.TRUE.equals(
                            jdbc.queryForObject(
                                    "SELECT EXISTS(SELECT 1 FROM kb_ingestion_job WHERE id=? AND status='RUNNING' AND lease_until>clock_timestamp())",
                                    Boolean.class,
                                    job))) continue;
                }
                Files.deleteIfExists(file);
            }
        } catch (Exception error) {
            // error：本次处理捕获的异常，用于归类失败原因。
            org.slf4j.LoggerFactory.getLogger(getClass())
                    .warn(
                            "Parser spool sweep deferred; files remain eligible for a later bounded scan");
        }
    }

    /**
     * 检查临时文件是否超过保留时间，避免清理新创建文件。
     *
     * @param path 当前需要读取、写入或清理的文件路径。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private boolean oldEnough(Path path) {
        try {
            return Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS)
                    .toInstant()
                    .isBefore(Instant.now().minus(Duration.ofMinutes(30)));
        } catch (java.io.IOException e) {
            // e：本次捕获或匹配的异常，用于保留业务分类及失败原因。
            return false;
        }
    }
}
