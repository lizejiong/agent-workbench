package com.lzj.rag.backend.parser.process;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lzj.rag.backend.knowledgebase.exception.KnowledgeBaseException;
import com.lzj.rag.backend.parser.config.FormatProperties;
import com.lzj.rag.backend.parser.service.bo.ParserInputBO;

import java.nio.file.*;
import java.util.Map;

/** 隔离解析子进程的入口，从请求文件读取参数并将结果写回指定文件。 */
public final class ParserProcessMain {

    /** 禁止实例化仅包含类型定义或静态方法的工具类。 */
    private ParserProcessMain() {}

    /**
     * 从指定请求文件恢复解析输入，执行本地提取并将 JSON 结果写回目标文件。
     *
     * @param args 命令行入口参数。
     */
    public static void main(String[] args) throws Exception {
        // json：用于请求、快照或元数据转换的 JSON 映射器。
        var json = new ObjectMapper().findAndRegisterModules();
        // request：当前入口接收的请求参数。
        var request = json.readTree(Files.readAllBytes(Path.of(args[0])));
        // output：本阶段产生的输出对象。
        Object output;
        try {
            // input：本阶段需要处理的输入内容或固定输入快照。
            var input = json.treeToValue(request.path("input"), ParserInputBO.class);
            // config：当前组件使用的配置或底层客户端参数构建器。
            var config = json.treeToValue(request.path("config"), FormatProperties.class);
            // parent：当前语法节点的父节点或解析子进程的父进程标识。
            long parent = request.path("parentPid").asLong();
            // watchdog：控制子进程执行时间的异步监控任务。
            var watchdog =
                    new Thread(
                            () -> {
                                while (true)
                                    try {
                                        Thread.sleep(1000);
                                        // p：解析子进程的父进程句柄，用于在父进程退出后停止解析。
                                        if (java.time.Instant.now().isAfter(input.deadline())
                                                || ProcessHandle.of(parent)
                                                        .map(p -> !p.isAlive())
                                                        .orElse(true))
                                            Runtime.getRuntime().halt(124);
                                    } catch (InterruptedException e) {
                                        // e：本次捕获或匹配的异常，用于保留业务分类及失败原因。
                                        return;
                                    }
                            },
                            "parser-parent-deadline");
            watchdog.setDaemon(true);
            watchdog.start();
            output = Map.of("document", LocalFormatExtractor.parse(input, config));
        } catch (Throwable error) {
            // kb：本次捕获或匹配的异常，用于保留业务分类及失败原因。
            // code：对外使用的稳定业务错误分类。
            // error：本次处理捕获的异常，用于归类失败原因。
            String code =
                    error instanceof KnowledgeBaseException kb ? kb.code() : "KB_PARSE_FAILED";
            // kb：本次捕获或匹配的异常，用于保留业务分类及失败原因。
            output =
                    Map.of(
                            "status",
                            error instanceof KnowledgeBaseException kb ? kb.status() : 422,
                            "code",
                            code,
                            "message",
                            "Local parsing failed or exceeded its limits; inspect the format matrix and source file");
        }
        Files.write(Path.of(args[1]), json.writeValueAsBytes(output));
    }
}
