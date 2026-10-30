package com.lzj.rag.backend.refresh.importing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lzj.rag.backend.document.service.DocumentService;
import com.lzj.rag.backend.document.service.bo.DocumentUploadBO;
import com.lzj.rag.backend.ingestion.service.IngestionJobService;
import com.lzj.rag.backend.knowledgebase.exception.KnowledgeBaseException;
import com.lzj.rag.backend.knowledgebase.service.KnowledgeBaseService;
import com.lzj.rag.backend.model.catalog.EmbeddingCatalog;
import com.lzj.rag.backend.parser.support.SupportedFiles;
import com.lzj.rag.backend.refresh.download.RemoteDownloader;
import com.lzj.rag.backend.refresh.service.bo.RemoteImportBO;
import com.lzj.rag.backend.refresh.service.bo.RemoteImportedBO;
import com.lzj.rag.backend.refresh.state.RefreshState;
import com.lzj.rag.backend.security.model.KnowledgeBasePrincipal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/** 以幂等导入记录协调受控下载、文档上传和可选的入库提交。 */
@Service
public class UrlImportService {

    /** 执行数据库查询及受条件保护的业务状态更新。 */
    private final JdbcTemplate jdbc;

    /** 负责知识库归属、版本与固定模型绑定的业务服务。 */
    private final KnowledgeBaseService bases;

    /** 负责文档归属、上传和配置维护的业务服务。 */
    private final DocumentService documents;

    /** 负责持久化入库任务及租约状态迁移的业务服务。 */
    private final IngestionJobService jobs;

    /** 负责刷新凭据、源对象切换及终态维护的组件。 */
    private final RefreshState state;

    /** 执行网络许可校验和有界下载的组件。 */
    private final RemoteDownloader downloader;

    /** 用于业务请求、持久化快照与元数据转换的 JSON 映射器。 */
    private final ObjectMapper json;

    /** 用于短时状态变更的事务模板。 */
    private final TransactionTemplate tx;

    /**
     * 初始化当前组件，保存业务依赖及本次使用的配置。
     *
     * @param jdbc 执行数据库查询及受条件保护的业务状态更新。
     * @param bases 负责知识库归属、版本与固定模型绑定的业务服务。
     * @param documents 负责文档归属、上传和配置维护的业务服务。
     * @param jobs 负责持久化入库任务及租约状态迁移的业务服务。
     * @param state 负责刷新凭据、源对象切换及终态维护的组件。
     * @param downloader 执行网络许可校验和有界下载的组件。
     * @param json 用于业务请求、持久化快照与元数据转换的 JSON 映射器。
     * @param transactions 创建后台短事务及只读事务的事务管理器。
     */
    public UrlImportService(
            JdbcTemplate jdbc,
            KnowledgeBaseService bases,
            DocumentService documents,
            IngestionJobService jobs,
            RefreshState state,
            RemoteDownloader downloader,
            ObjectMapper json,
            PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.bases = bases;
        this.documents = documents;
        this.jobs = jobs;
        this.state = state;
        this.downloader = downloader;
        this.json = json;
        tx = new TransactionTemplate(transactions);
        tx.setTimeout(10);
    }

    /**
     * 按幂等键领取导入权，受控下载并创建文档，按请求决定是否提交入库。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param kb 本次操作涉及的知识库唯一标识。
     * @param key 对象存储键、配置键或业务幂等键。
     * @param request 当前入口接收的请求参数。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public RemoteImportedBO importUrl(
            KnowledgeBasePrincipal actor, UUID kb, String key, RemoteImportBO request) {
        bases.requireOwned(actor, kb);
        if (key == null || !key.matches("[A-Za-z0-9_.:-]{16,128}"))
            throw new KnowledgeBaseException(
                    400,
                    "KB_INVALID_INPUT",
                    "Idempotency-Key must contain 16..128 safe ASCII characters");
        // url：经过协议、主机和端口许可校验的远程地址。
        String url = downloader.validateUrl(request.url());
        validateFilename(request.filename());
        // payload：需要持久化或发送的 JSON 正文。
        String payload =
                EmbeddingCatalog.sha256(
                        encode(
                                List.of(
                                        url,
                                        request.filename(),
                                        Objects.toString(request.name(), ""),
                                        request.ingestionSpec(),
                                        request.ingest())));
        // operation：持久化上传操作、操作标识或审计动作名称。
        var operation =
                tx.execute(
                        status -> {
                            // status：本次短事务的执行状态，由事务模板传入。
                            bases.lockOwned(actor, kb);
                            // rows：数据库结果或当前表格数据行集合。
                            var rows =
                                    jdbc.queryForList(
                                            "SELECT *,lease_until>clock_timestamp() AS valid FROM kb_url_import WHERE kb_id=? AND tenant_id=? AND owner_id=? AND request_id=? FOR UPDATE",
                                            kb,
                                            actor.tenantId(),
                                            actor.userId(),
                                            key);
                            if (!rows.isEmpty()) {
                                // old：需要替换的旧记录或旧业务状态。
                                var old = rows.get(0);
                                if (!payload.equals(old.get("payload_hash")))
                                    throw new KnowledgeBaseException(
                                            409,
                                            "KB_IDEMPOTENCY_CONFLICT",
                                            "Import key is bound to a different request");
                                if ("RUNNING".equals(old.get("status"))
                                        && !Boolean.TRUE.equals(old.get("valid"))) {
                                    jdbc.update(
                                            "UPDATE kb_url_import SET status='FAILED',error_code='KB_IMPORT_EXPIRED' WHERE id=?",
                                            old.get("id"));
                                    old.put("status", "FAILED");
                                }
                                return old;
                            }
                            // id：当前业务对象的唯一标识。
                            UUID id = UUID.randomUUID();
                            jdbc.update(
                                    "INSERT INTO kb_url_import(id,kb_id,tenant_id,owner_id,request_id,payload_hash,status,lease_until) VALUES (?,?,?,?,?,?,'RUNNING',clock_timestamp()+interval '10 minutes')",
                                    id,
                                    kb,
                                    actor.tenantId(),
                                    actor.userId(),
                                    key,
                                    payload);
                            return new HashMap<String, Object>(Map.of("id", id, "status", "NEW"));
                        });
        if ("COMPLETED".equals(operation.get("status")))
            return new RemoteImportedBO(
                    documents.get(actor, (UUID) operation.get("document_id")),
                    (UUID) operation.get("job_id"),
                    true);
        if (!"NEW".equals(operation.get("status")))
            throw new KnowledgeBaseException(
                    409,
                    "KB_IMPORT_NOT_READY",
                    "Import is running or previously failed; inspect document/storage operations and use a new key after recovery");
        // id：当前业务对象的唯一标识。
        UUID id = (UUID) operation.get("id");
        // download：当前持有的远程下载结果及临时文件。
        try (var download = downloader.download(url, null, null, null)) {
            // metadata：上传业务元数据或切片来源定位字段。
            var metadata = new DocumentUploadBO(request.name(), request.ingestionSpec());
            // uploaded：已经完成对象写入及文档创建的上传结果。
            var uploaded =
                    documents.uploadSource(
                            actor,
                            kb,
                            download.file(request.filename()),
                            encode(metadata),
                            "url-" + id,
                            url,
                            document -> {
                                // document：当前处理的文档记录或文档标识。
                                if (jdbc.update(
                                                "UPDATE kb_url_import SET status='COMPLETED',document_id=?,object_key=?,etag=?,last_modified=? WHERE id=? AND status='RUNNING' AND lease_until>clock_timestamp()",
                                                document.id(),
                                                document.objectKey(),
                                                download.etag(),
                                                download.lastModified(),
                                                id)
                                        != 1)
                                    throw new KnowledgeBaseException(
                                            409,
                                            "KB_IMPORT_EXPIRED",
                                            "Import no longer owns its reservation");
                                state.ensure(document.id());
                                if (request.ingest()) {
                                    // job：当前入库任务记录或任务标识。
                                    UUID job =
                                            jobs.submit(actor, document.id(), "import-" + id, null)
                                                    .job()
                                                    .id();
                                    jdbc.update(
                                            "UPDATE kb_url_import SET job_id=? WHERE id=?",
                                            job,
                                            id);
                                }
                            });
            // job：当前入库任务记录或任务标识。
            UUID job =
                    jdbc.queryForObject(
                            "SELECT job_id FROM kb_url_import WHERE id=?", UUID.class, id);
            return new RemoteImportedBO(documents.get(actor, uploaded.document().id()), job, false);
        } catch (RuntimeException error) {
            // kbError：本次捕获或匹配的异常，用于保留业务分类及失败原因。
            // error：本次处理捕获的异常，用于归类失败原因。
            jdbc.update(
                    "UPDATE kb_url_import SET status='FAILED',error_code=? WHERE id=? AND status='RUNNING'",
                    error instanceof KnowledgeBaseException kbError
                            ? kbError.code()
                            : "KB_IMPORT_FAILED",
                    id);
            throw error;
        }
    }

    /**
     * 检查远程导入文件名的扩展名和安全性。
     *
     * @param name 当前业务资源或策略的公开名称。
     */
    public static void validateFilename(String name) {
        if (name == null
                || name.isBlank()
                || name.length() > 255
                || !name.equals(name.strip())
                || name.contains("/")
                || name.contains("\\")
                || name.codePoints().anyMatch(Character::isISOControl))
            throw new KnowledgeBaseException(
                    400, "KB_INVALID_INPUT", "Provide a safe filename with a supported extension");
        SupportedFiles.extensionAllowed(name);
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
}
