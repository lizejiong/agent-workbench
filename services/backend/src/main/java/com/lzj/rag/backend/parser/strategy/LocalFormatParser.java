package com.lzj.rag.backend.parser.strategy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lzj.rag.backend.knowledgebase.exception.KnowledgeBaseException;
import com.lzj.rag.backend.parser.config.FormatProperties;
import com.lzj.rag.backend.parser.process.ParserProcessMain;
import com.lzj.rag.backend.parser.service.bo.ParsedDocumentBO;
import com.lzj.rag.backend.parser.service.bo.ParserInputBO;
import com.lzj.rag.backend.parser.support.SupportedFiles;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.system.ApplicationHome;
import org.springframework.stereotype.Component;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** 通过有界子进程解析本地格式，控制堆内存、时间及返回文件大小。 */
@Component
@EnableConfigurationProperties(FormatProperties.class)
public class LocalFormatParser implements DocumentParser {

    /** 本地解析、MinerU 和视觉模型的开关、容量与时间限制。 */
    private final FormatProperties config;

    /** 用于业务请求、持久化快照与元数据转换的 JSON 映射器。 */
    private final ObjectMapper json;

    /** 限制当前业务并发执行数量的许可集合。 */
    private final Semaphore permits;

    /**
     * 初始化当前组件，保存业务依赖及本次使用的配置。
     *
     * @param config 本地解析、MinerU 和视觉模型的开关、容量与时间限制。
     * @param json 用于业务请求、持久化快照与元数据转换的 JSON 映射器。
     */
    public LocalFormatParser(FormatProperties config, ObjectMapper json) {
        this.config = config;
        this.json = json;
        permits = new Semaphore(config.concurrency());
    }

    /**
     * 返回解析器的稳定策略标识。
     *
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public String profile() {
        return "BINARY";
    }

    /**
     * 返回当前解析策略支持的 MIME 类型集合。
     *
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public Set<String> mimeTypes() {
        // types：本地二进制解析器支持的 MIME 类型集合。
        var types = new HashSet<>(SupportedFiles.OFFICE);
        types.addAll(SupportedFiles.EXCEL);
        return Set.copyOf(types);
    }

    /**
     * 拒绝未经文件校验的纯文本输入；该解析策略要求使用受限文件输入。
     *
     * @param text 当前待处理或生成的文本内容。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public ParsedDocumentBO parse(String text) {
        throw new KnowledgeBaseException(
                415, "KB_PARSER_UNAVAILABLE", "Binary parser requires a verified file");
    }

    /**
     * 按当前解析策略读取输入，在容量与时间限制内生成统一结构块。
     *
     * @param input 解析源文件及其字符、时间约束。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @Override
    public ParsedDocumentBO parse(ParserInputBO input) {
        // 第一阶段：共享上层截止时间，在并发许可内执行一次本地解析。
        // acquired：是否已经取得本次操作所需并发许可。
        boolean acquired = false;
        // process：执行格式解析的受限子进程。
        Process process = null;
        // request：当前入口接收的请求参数。
        Path request = null;
        // result：当前步骤产生的处理结果。
        Path result = null;
        // deadline：本阶段或本次请求必须完成的截止时间。
        Instant deadline = Instant.now().plus(config.localTimeout());
        if (input.deadline().isBefore(deadline)) deadline = input.deadline();
        try {
            // wait：本次等待允许使用的时间量。
            long wait = Duration.between(Instant.now(), deadline).toMillis();
            if (wait <= 0 || !(acquired = permits.tryAcquire(wait, TimeUnit.MILLISECONDS)))
                throw timeout();
            request =
                    Files.createTempFile(
                            input.path().toAbsolutePath().getParent(),
                            "kb-parser-request-",
                            ".json");
            result = Path.of(request + ".result");
            // bounded：按当前操作截止时间收紧预算后的查询参数。
            var bounded =
                    new ParserInputBO(
                            input.path(),
                            input.filename(),
                            input.mime(),
                            input.maxParsedChars(),
                            input.requestId(),
                            deadline);
            // childConfig：写入子进程请求文件的受限解析配置。
            // 子进程参数和日志只包含临时请求文件位置，密钥及二进制正文不进入命令行。
            var childConfig = json.valueToTree(config);
            ((com.fasterxml.jackson.databind.node.ObjectNode) childConfig)
                    .remove(List.of("mineru", "vlm"));
            Files.write(
                    request,
                    json.writeValueAsBytes(
                            Map.of(
                                    "input",
                                    bounded,
                                    "config",
                                    childConfig,
                                    "parentPid",
                                    ProcessHandle.current().pid())));
            // 第二阶段：为子进程设置堆内存和编码限制，只通过临时请求文件传递输入。
            // command：启动格式解析子进程的命令参数集合。
            var command = new ArrayList<String>();
            command.add(
                    Path.of(
                                    System.getProperty("java.home"),
                                    "bin",
                                    System.getProperty("os.name").startsWith("Windows")
                                            ? "java.exe"
                                            : "java")
                            .toString());
            command.add("-Xmx" + config.processHeapMb() + "m");
            command.add("-Dfile.encoding=UTF-8");
            command.add("-Djava.awt.headless=true");
            // source：当前结构块的来源字段或已校验临时源文件。
            var source = new ApplicationHome(ParserProcessMain.class).getSource();
            if (source != null && source.isFile() && source.getName().endsWith(".jar")) {
                command.add("-Dloader.main=" + ParserProcessMain.class.getName());
                command.add("-cp");
                command.add(source.getAbsolutePath());
                command.add("org.springframework.boot.loader.launch.PropertiesLauncher");
            } else {
                command.add("-cp");
                command.add(System.getProperty("java.class.path"));
                command.add(ParserProcessMain.class.getName());
            }
            command.add(request.toString());
            command.add(result.toString());
            process =
                    new ProcessBuilder(command)
                            .redirectError(ProcessBuilder.Redirect.DISCARD)
                            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                            .start();
            wait = Duration.between(Instant.now(), deadline).toMillis();
            if (wait <= 0 || !process.waitFor(wait, TimeUnit.MILLISECONDS)) throw timeout();
            // 第三阶段：读取结果前检查文件存在与大小，拒绝失败或被截断的解析输出。
            if (!Files.exists(result) || Files.size(result) > config.maxResultBytes())
                throw invalid();
            // response：当前 HTTP 响应或外部服务返回结果。
            var response = json.readTree(Files.readAllBytes(result));
            if (response.has("code"))
                throw new KnowledgeBaseException(
                        response.path("status").asInt(422),
                        response.path("code").asText("KB_PARSE_FAILED"),
                        "Local parsing failed; check source format, formulas and parsing limits");
            // parsed：源文件解析得到的统一结构化文档。
            var parsed = json.treeToValue(response.path("document"), ParsedDocumentBO.class);
            if (parsed == null
                    || parsed.text() == null
                    || parsed.blocks() == null
                    || parsed.text().length() > input.maxParsedChars()) throw invalid();
            return parsed;
        } catch (KnowledgeBaseException e) {
            // e：本次捕获或匹配的异常，用于保留业务分类及失败原因。
            throw e;
        } catch (InterruptedException e) {
            // e：本次捕获或匹配的异常，用于保留业务分类及失败原因。
            Thread.currentThread().interrupt();
            throw timeout();
        } catch (Exception e) {
            // e：本次捕获或匹配的异常，用于保留业务分类及失败原因。
            throw invalid();
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
                try {
                    process.waitFor(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    // e：本次捕获或匹配的异常，用于保留业务分类及失败原因。
                    Thread.currentThread().interrupt();
                }
            }
            remove(request);
            remove(result);
            if (acquired) permits.release();
        }
    }

    /**
     * 在进入子进程解析前校验 PDF 页数等资源约束。
     *
     * @param input 解析源文件及其字符、时间约束。
     */
    public void validatePdf(ParserInputBO input) {
        parse(
                new ParserInputBO(
                        input.path(),
                        input.filename(),
                        "application/x-kb-pdf-inspection",
                        input.maxParsedChars(),
                        input.requestId(),
                        input.deadline()));
    }

    /**
     * 尽力回收请求或结果临时文件，失败时留给定时清理。
     *
     * @param path 当前需要读取、写入或清理的文件路径。
     */
    private void remove(Path path) {
        if (path != null)
            try {
                Files.deleteIfExists(path);
            } catch (java.io.IOException e) {
                // e：本次捕获或匹配的异常，用于保留业务分类及失败原因。
                org.slf4j.LoggerFactory.getLogger(getClass())
                        .warn("Parser temporary artifact awaits bounded spool sweep");
            }
    }

    /**
     * 创建调用预算耗尽的超时异常。
     *
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private static KnowledgeBaseException timeout() {
        return new KnowledgeBaseException(
                504, "KB_PARSE_TIMEOUT", "Local parsing timed out or was interrupted");
    }

    /**
     * 创建输入或协议校验失败的业务异常。
     *
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private static KnowledgeBaseException invalid() {
        return new KnowledgeBaseException(
                422,
                "KB_PARSE_FAILED",
                "Local parser failed or exceeded its bounded result/heap budget");
    }
}
