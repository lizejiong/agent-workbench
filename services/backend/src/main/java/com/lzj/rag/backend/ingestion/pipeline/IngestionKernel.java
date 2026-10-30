package com.lzj.rag.backend.ingestion.pipeline;

import com.lzj.rag.backend.chunk.service.bo.ChunkBO;
import com.lzj.rag.backend.chunk.splitting.ChunkingService;
import com.lzj.rag.backend.document.storage.DocumentStorage;
import com.lzj.rag.backend.infra.storage.config.StorageProperties;
import com.lzj.rag.backend.ingestion.config.IngestionProperties;
import com.lzj.rag.backend.ingestion.publishing.ChunkIndexWriter;
import com.lzj.rag.backend.ingestion.service.IngestionJobService;
import com.lzj.rag.backend.ingestion.service.bo.IngestionContextBO;
import com.lzj.rag.backend.ingestion.service.bo.IngestionResultBO;
import com.lzj.rag.backend.knowledgebase.exception.KnowledgeBaseException;
import com.lzj.rag.backend.model.service.EmbeddingClient;
import com.lzj.rag.backend.operations.logging.OperationLog;
import com.lzj.rag.backend.parser.routing.ParserRegistry;
import com.lzj.rag.backend.parser.service.bo.ParsedDocumentBO;
import com.lzj.rag.backend.parser.service.bo.ParserInputBO;
import com.lzj.rag.backend.parser.support.SupportedFiles;
import com.lzj.rag.backend.security.model.KnowledgeBasePrincipal;

import org.springframework.stereotype.Service;

import java.io.*;
import java.security.*;
import java.time.Instant;
import java.util.*;

/** 编排源文件读取、解析、切片、嵌入和事务发布，并在阶段之间检查执行预算。 */
@Service
public class IngestionKernel {

    /**
     * 经过完整性校验的临时源文件，关闭时回收本地副本。
     *
     * @param path 当前需要读取、写入或清理的文件路径。
     * @param mime 识别或选择的文件 MIME 类型。
     */
    private record Source(java.nio.file.Path path, String mime) implements AutoCloseable {

        /** 释放当前对象持有的临时文件或执行资源。 */
        public void close() {
            remove(path);
        }
    }

    /** 负责持久化入库任务及租约状态迁移的业务服务。 */
    private final IngestionJobService jobs;

    /** 按真实格式和处理档位选择解析策略的注册器。 */
    private final ParserRegistry parsers;

    /** 将结构块转换为有界切片的业务组件。 */
    private final ChunkingService chunking;

    /** 受固定模型身份约束的嵌入客户端。 */
    private final EmbeddingClient embeddings;

    /** 在事务内写入并发布新代次的组件。 */
    private final ChunkIndexWriter writer;

    /** 支持流式读写及幂等删除的源对象存储组件。 */
    private final DocumentStorage storage;

    /** 入库流水线的时间预算、文本长度、切片数量和向量写入限制。 */
    private final IngestionProperties limits;

    /** 对象存储连接、文件容量、临时目录及上传补偿配置。 */
    private final StorageProperties storageLimits;

    /**
     * 初始化当前组件，保存业务依赖及本次使用的配置。
     *
     * @param jobs 负责持久化入库任务及租约状态迁移的业务服务。
     * @param parsers 按真实格式和处理档位选择解析策略的注册器。
     * @param chunking 将结构块转换为有界切片的业务组件。
     * @param embeddings 受固定模型身份约束的嵌入客户端。
     * @param writer 在事务内写入并发布新代次的组件。
     * @param storage 支持流式读写及幂等删除的源对象存储组件。
     * @param limits 入库流水线的时间预算、文本长度、切片数量和向量写入限制。
     * @param storageLimits 对象存储连接、文件容量、临时目录及上传补偿配置。
     */
    public IngestionKernel(
            IngestionJobService jobs,
            ParserRegistry parsers,
            ChunkingService chunking,
            EmbeddingClient embeddings,
            ChunkIndexWriter writer,
            DocumentStorage storage,
            IngestionProperties limits,
            StorageProperties storageLimits) {
        this.jobs = jobs;
        this.parsers = parsers;
        this.chunking = chunking;
        this.embeddings = embeddings;
        this.writer = writer;
        this.storage = storage;
        this.limits = limits;
        this.storageLimits = storageLimits;
    }

    /**
     * 创建内部单次入库任务并执行标准流水线。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param documentId 文档唯一标识。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public IngestionResultBO ingest(KnowledgeBasePrincipal actor, UUID documentId) {
        return execute(jobs.start(actor, documentId));
    }

    /**
     * 按读取、解析、切片、嵌入、发布顺序执行本次尝试，失败时收敛任务状态。
     *
     * @param context 本次入库尝试固定的输入快照及租约凭据。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public IngestionResultBO execute(IngestionContextBO context) {
        // 进入流水线：数据库保证同一凭据只执行一次。
        // 一次性进入校验位于失败处理之外，重复投递不能把正在正常执行的任务标记为失败。
        jobs.enter(context);
        try {
            // 第一阶段：有界读取源对象，并校验内容摘要与真实文件格式。
            jobs.begin(context, "FETCH", 1, "objects");
            // parsed：已解析的有序结构块及来源信息。
            ParsedDocumentBO parsed;
            // source：通过摘要及格式校验的临时源文件，离开当前作用域时回收。
            try (var source = read(context)) {
                jobs.end(context, "FETCH", context.fileSize(), "bytes");
                deadline(context);
                // 第二阶段：按真实 MIME 和配置选择解析器，临时源文件在解析结束后回收。
                jobs.begin(context, "PARSE", context.fileSize(), "bytes");
                parsed =
                        parsers.resolve(
                                        source.mime(),
                                        context.spec().parser(),
                                        context.originalFilename(),
                                        context.spec().parseProfile())
                                .parse(
                                        new ParserInputBO(
                                                source.path(),
                                                context.originalFilename(),
                                                source.mime(),
                                                limits.maxParsedChars(),
                                                context.requestId(),
                                                context.deadline()));
                jobs.end(context, "PARSE", parsed.blocks().size(), "blocks");
            }
            deadline(context);
            // 第三阶段：按结构边界切片，保留标题与原文定位信息。
            jobs.begin(context, "SPLIT", parsed.blocks().size(), "blocks");
            // chunks：本次处理的有序切片集合。
            var chunks =
                    chunking.chunk(
                            context.documentId(),
                            context.generation(),
                            parsed,
                            context.spec().splitter());
            jobs.end(context, "SPLIT", chunks.size(), "chunks");
            deadline(context);
            // 第四阶段：整组切片共用固定模型绑定，并将实际 AI 修订固定到任务。
            jobs.begin(context, "EMBED", chunks.size(), "texts");
            // result：顺序对应切片的整组嵌入向量及实际 AI 配置修订。
            var result =
                    embeddings.embed(
                            context.binding(),
                            chunks.stream().map(ChunkBO::embeddingText).toList(),
                            context.requestId(),
                            context.deadline());
            jobs.pinAiRevision(context, result.aiConfigRevision());
            jobs.end(context, "EMBED", result.vectors().size(), "vectors");
            deadline(context);
            // 第五阶段：交给事务发布器写入新代次，成功后才更新当前可检索版本。
            jobs.begin(context, "PERSIST", chunks.size(), "chunks");
            writer.writeAndPublish(context, chunks, result);
            OperationLog.committed(
                    context.kbId(),
                    context.documentId(),
                    context.jobId(),
                    context.generation(),
                    "COMPLETE",
                    "SUCCEEDED");
            return new IngestionResultBO(
                    context.jobId(), context.generation(), chunks, result.aiConfigRevision());
        } catch (RuntimeException error) {
            // e：本次捕获或匹配的异常，用于保留业务分类及失败原因。
            // error：本次处理捕获的异常，用于归类失败原因。
            org.slf4j.LoggerFactory.getLogger(getClass())
                    .warn(
                            "kbId={} documentId={} jobId={} generation={} stage=EXECUTION errorCode={}",
                            context.kbId(),
                            context.documentId(),
                            context.jobId(),
                            context.generation(),
                            error instanceof KnowledgeBaseException e
                                    ? e.code()
                                    : "KB_INGESTION_FAILED");
            try {
                // 失败收敛：仅持有有效执行权的尝试可以更新任务；恢复失败不覆盖原异常。
                jobs.fail(context, error);
            } catch (RuntimeException ignored) {
                // ignored：回收或补偿过程中捕获的异常，不覆盖原业务处理结果。
                org.slf4j.LoggerFactory.getLogger(getClass())
                        .warn("Ingestion failure state awaits recovery jobId={}", context.jobId());
            }
            throw error;
        }
    }

    /**
     * 将源对象流式复制到临时文件，校验大小、摘要及真实文件格式。
     *
     * @param context 本次入库尝试固定的输入快照及租约凭据。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private Source read(IngestionContextBO context) {
        // spool：当前尝试持有的磁盘临时文件。
        java.nio.file.Path spool = null;
        try {
            // directory：临时文件所在目录。
            var directory = storageLimits.tempDirectory();
            if (directory == null || directory.toString().isBlank())
                directory = java.nio.file.Path.of(System.getProperty("java.io.tmpdir"));
            java.nio.file.Files.createDirectories(directory);
            spool =
                    java.nio.file.Files.createTempFile(
                            directory,
                            "kb-source-" + context.jobId() + "-" + context.claimToken() + "-",
                            ".tmp");
            // digest：用于流式累计 SHA-256 摘要的计算器。
            var digest = MessageDigest.getInstance("SHA-256");
            // count：当前累计数量或本次流读取的有效字节数。
            long count = 0;
            // output：本次源文件临时副本的磁盘输出流。
            // input：对象存储返回的源文件输入流。
            try (var input = storage.open(context.objectKey());
                    var output = java.nio.file.Files.newOutputStream(spool)) {
                // buffer：用于分块读写的固定大小缓冲区。
                byte[] buffer = new byte[8192];
                // n：本次流读取返回的有效字节或字符数量。
                int n;
                while ((n = input.read(buffer)) != -1) {
                    deadline(context);
                    count += n;
                    if (count > storageLimits.maxUploadBytes() || count > context.fileSize())
                        throw new KnowledgeBaseException(
                                422,
                                "KB_SOURCE_TOO_LARGE",
                                "Stored source exceeds bounded file budget");
                    digest.update(buffer, 0, n);
                    output.write(buffer, 0, n);
                }
            }
            if (count != context.fileSize()
                    || !HexFormat.of().formatHex(digest.digest()).equals(context.contentHash()))
                throw new KnowledgeBaseException(
                        409,
                        "KB_SOURCE_CHANGED",
                        "Stored object bytes differ from upload metadata");
            // mime：识别或选择的文件 MIME 类型。
            String mime = SupportedFiles.detect(spool);
            if (!mime.equals(context.mimeType()))
                throw new KnowledgeBaseException(
                        409,
                        "KB_SOURCE_CHANGED",
                        "Stored object MIME differs from upload metadata");
            SupportedFiles.compatible(context.originalFilename(), mime);
            return new Source(spool, mime);
        } catch (RuntimeException error) {
            // error：本次处理捕获的异常，用于归类失败原因。
            remove(spool);
            throw error;
        } catch (IOException | NoSuchAlgorithmException error) {
            // error：本次处理捕获的异常，用于归类失败原因。
            remove(spool);
            throw new KnowledgeBaseException(
                    503,
                    "KB_STORAGE_UNAVAILABLE",
                    "Stored source staging or download failed temporarily");
        }
    }

    /**
     * 尽力删除本次源文件副本，失败时保留后台回收机会。
     *
     * @param path 当前需要读取、写入或清理的文件路径。
     */
    private static void remove(java.nio.file.Path path) {
        if (path != null)
            try {
                java.nio.file.Files.deleteIfExists(path);
            } catch (IOException e) {
                // e：本次捕获或匹配的异常，用于保留业务分类及失败原因。
                org.slf4j.LoggerFactory.getLogger(IngestionKernel.class)
                        .warn("Source spool awaits bounded cleanup sweep");
            }
    }

    /**
     * 检查工作线程中断和本次尝试截止时间，及时停止失效执行。
     *
     * @param context 本次入库尝试固定的输入快照及租约凭据。
     */
    private static void deadline(IngestionContextBO context) {
        if (Thread.currentThread().isInterrupted())
            throw new KnowledgeBaseException(
                    409, "KB_JOB_INTERRUPTED", "Ingestion execution interrupted");
        if (!Instant.now().isBefore(context.deadline()))
            throw new KnowledgeBaseException(
                    504, "KB_INGESTION_TIMEOUT", "Ingestion attempt deadline exceeded");
    }
}
