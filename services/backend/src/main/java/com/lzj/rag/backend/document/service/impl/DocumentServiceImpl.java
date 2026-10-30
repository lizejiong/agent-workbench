package com.lzj.rag.backend.document.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lzj.rag.backend.document.dao.entity.DocumentDO;
import com.lzj.rag.backend.document.dao.mapper.DocumentMapper;
import com.lzj.rag.backend.document.service.DocumentService;
import com.lzj.rag.backend.document.service.bo.DocumentBO;
import com.lzj.rag.backend.document.service.bo.DocumentPatchBO;
import com.lzj.rag.backend.document.service.bo.DocumentUploadBO;
import com.lzj.rag.backend.document.service.bo.DocumentUploadedBO;
import com.lzj.rag.backend.document.service.bo.IngestionSpecBO;
import com.lzj.rag.backend.document.service.bo.UploadSourceBO;
import com.lzj.rag.backend.document.storage.DocumentStorage;
import com.lzj.rag.backend.document.upload.PreparedUpload;
import com.lzj.rag.backend.document.upload.UploadOperations;
import com.lzj.rag.backend.infra.storage.config.StorageProperties;
import com.lzj.rag.backend.knowledgebase.exception.KnowledgeBaseException;
import com.lzj.rag.backend.knowledgebase.service.KnowledgeBaseService;
import com.lzj.rag.backend.model.catalog.EmbeddingCatalog;
import com.lzj.rag.backend.parser.routing.ParserRegistry;
import com.lzj.rag.backend.parser.support.SupportedFiles;
import com.lzj.rag.backend.security.model.KnowledgeBasePrincipal;
import com.lzj.rag.framework.convention.page.PageResponse;

import jakarta.validation.Validator;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

/** 文档业务服务，负责上传校验、归属校验、配置变更和响应转换。 */
@Service
public class DocumentServiceImpl implements DocumentService {

    /** 文档实体数据访问接口。 */
    private final DocumentMapper mapper;

    /** 负责知识库归属、版本与固定模型绑定的业务服务。 */
    private final KnowledgeBaseService bases;

    /** 负责上传预留和失败对象补偿的业务组件。 */
    private final UploadOperations operations;

    /** 支持流式读写及幂等删除的源对象存储组件。 */
    private final DocumentStorage storage;

    /** 对象存储连接、文件容量、临时目录及上传补偿配置。 */
    private final StorageProperties properties;

    /** 用于业务请求、持久化快照与元数据转换的 JSON 映射器。 */
    private final ObjectMapper json;

    /** 文档请求的 Bean Validation 校验器。 */
    private final Validator validator;

    /** 按真实格式和处理档位选择解析策略的注册器。 */
    private final ParserRegistry parsers;

    /**
     * 初始化当前组件，保存业务依赖及本次使用的配置。
     *
     * @param mapper 文档实体数据访问接口。
     * @param bases 负责知识库归属、版本与固定模型绑定的业务服务。
     * @param operations 负责上传预留和失败对象补偿的业务组件。
     * @param storage 支持流式读写及幂等删除的源对象存储组件。
     * @param properties 对象存储连接、文件容量、临时目录及上传补偿配置。
     * @param json 用于业务请求、持久化快照与元数据转换的 JSON 映射器。
     * @param validator 文档请求的 Bean Validation 校验器。
     * @param parsers 按真实格式和处理档位选择解析策略的注册器。
     */
    public DocumentServiceImpl(
            DocumentMapper mapper,
            KnowledgeBaseService bases,
            UploadOperations operations,
            DocumentStorage storage,
            StorageProperties properties,
            ObjectMapper json,
            Validator validator,
            ParserRegistry parsers) {
        this.mapper = mapper;
        this.bases = bases;
        this.operations = operations;
        this.storage = storage;
        this.properties = properties;
        this.json = json;
        this.validator = validator;
        this.parsers = parsers;
    }

    /**
     * 按本地文件来源执行文档上传业务。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param kb 本次操作涉及的知识库唯一标识。
     * @param file 调用方上传的文件输入，后续按流读取并检查大小及格式。
     * @param rawMetadata 客户端上传的原始元数据 JSON。
     * @param key 对象存储键、配置键或业务幂等键。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public DocumentUploadedBO upload(
            KnowledgeBasePrincipal actor,
            UUID kb,
            UploadSourceBO file,
            String rawMetadata,
            String key) {
        // view：当前业务记录转换得到的公开响应。
        return uploadSource(actor, kb, file, rawMetadata, key, null, view -> {});
    }

    /**
     * 校验源文件与处理配置，预留上传操作后存储对象并提交文档记录。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param kb 本次操作涉及的知识库唯一标识。
     * @param file 调用方上传的文件输入，后续按流读取并检查大小及格式。
     * @param rawMetadata 客户端上传的原始元数据 JSON。
     * @param key 对象存储键、配置键或业务幂等键。
     * @param sourceUrl 远程文档配置的原始源地址。
     * @param onCreated 文档成功创建后在当前业务流程内调用的回调。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public DocumentUploadedBO uploadSource(
            KnowledgeBasePrincipal actor,
            UUID kb,
            UploadSourceBO file,
            String rawMetadata,
            String key,
            String sourceUrl,
            java.util.function.Consumer<DocumentBO> onCreated) {
        bases.requireOwned(actor, kb);
        if (!properties.enabled())
            throw new KnowledgeBaseException(
                    503, "KB_FEATURE_DISABLED", "Object storage is not enabled");
        if (key == null || !key.matches("[A-Za-z0-9_.:-]{16,128}"))
            throw invalid("Idempotency-Key must contain 16-128 safe ASCII characters");
        // metadata：上传业务元数据或切片来源定位字段。
        DocumentUploadBO metadata = metadata(rawMetadata);
        // 上传第一阶段：复用有界临时文件校验，失败时自动回收本地副本。
        // prepared：完成大小、摘要及真实格式校验的临时上传文件。
        try (var prepared = PreparedUpload.prepare(file, properties)) {
            // name：当前业务资源或策略的公开名称。
            String name =
                    metadata.name() == null
                            ? prepared.filename
                            : PreparedUpload.safeName(metadata.name());
            checkParser(metadata.ingestionSpec(), prepared.filename);
            parsers.resolve(
                    prepared.mime,
                    metadata.ingestionSpec().parser(),
                    prepared.filename,
                    metadata.ingestionSpec().parseProfile());
            // spec：文档入库配置或当前切片策略参数。
            String spec = write(metadata.ingestionSpec());
            // payload：需要持久化或发送的 JSON 正文。
            String payload =
                    EmbeddingCatalog.sha256(
                            write(
                                    List.of(
                                            prepared.hash,
                                            prepared.filename,
                                            name,
                                            metadata.ingestionSpec())));
            // operation：持久化上传操作、操作标识或审计动作名称。
            var operation = operations.reserve(actor, kb, key, payload, name);
            if (operation.replayed())
                return new DocumentUploadedBO(view(requireOwned(actor, operation.id())), true);
            try {
                // 上传第二阶段：写入源对象；数据库记录提交失败时通过持久化操作执行补偿。
                storage.put(operation.objectKey(), prepared.path, prepared.mime, prepared.hash);
                // result：当前步骤产生的处理结果。
                DocumentBO result =
                        operations.complete(
                                actor,
                                kb,
                                operation,
                                () -> {
                                    operations.checkName(kb, name, null);
                                    // doc：当前操作涉及的文档。
                                    var doc = new DocumentDO();
                                    doc.setId(operation.id());
                                    doc.setKbId(kb);
                                    doc.setTenantId(actor.tenantId());
                                    doc.setOwnerId(actor.userId());
                                    doc.setName(name);
                                    doc.setOriginalFilename(prepared.filename);
                                    doc.setSourceType(sourceUrl == null ? "UPLOAD" : "URL");
                                    doc.setSourceUrl(sourceUrl);
                                    doc.setObjectKey(operation.objectKey());
                                    doc.setMimeType(prepared.mime);
                                    doc.setFileSize(prepared.size);
                                    doc.setContentHash(prepared.hash);
                                    doc.setEnabled(true);
                                    doc.setProcessStatus("PENDING");
                                    doc.setIngestionSpec(spec);
                                    doc.setConfigRevision(1L);
                                    doc.setManualRevision(0L);
                                    doc.setHasManualChanges(false);
                                    doc.setChunkCount(0L);
                                    doc.setVersion(1L);
                                    doc.setCreatedBy(actor.userId());
                                    doc.setUpdatedBy(actor.userId());
                                    doc.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
                                    doc.setUpdatedAt(doc.getCreatedAt());
                                    mapper.insert(doc);
                                    onCreated.accept(view(doc));
                                    return view(doc);
                                });
                return new DocumentUploadedBO(result, false);
            } catch (RuntimeException error) {
                // error：本次处理捕获的异常，用于归类失败原因。
                operations.compensate(actor, kb, operation.id());
                throw error;
            }
        }
    }

    /**
     * 按归属、处理状态和名称筛选条件分页查询文档。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param kb 本次操作涉及的知识库唯一标识。
     * @param current 当前分页页码，从一开始。
     * @param size 分页容量或当前数据的字节数。
     * @param name 当前业务资源或策略的公开名称。
     * @param status 当前业务操作或任务状态。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 10)
    public PageResponse<DocumentBO> list(
            KnowledgeBasePrincipal actor,
            UUID kb,
            int current,
            int size,
            String name,
            String status) {
        bases.requireOwned(actor, kb);
        if (!status.isEmpty()
                && !Set.of("PENDING", "PROCESSING", "READY", "FAILED").contains(status))
            throw invalid("Unknown document process status");
        // filter：包含租户、所有者及当前业务筛选条件的查询约束。
        var filter = owned(actor).eq(DocumentDO::getKbId, kb);
        if (!name.isEmpty()) filter.apply("strpos(lower(name), lower({0})) > 0", name);
        if (!status.isEmpty()) filter.eq(DocumentDO::getProcessStatus, status);
        // total：满足当前查询条件的记录总数。
        long total = mapper.selectCount(filter);
        // records：当前存储记录集合或证据重载组件。
        var records =
                mapper.selectList(
                        filter.orderByDesc(DocumentDO::getCreatedAt, DocumentDO::getId)
                                .last(
                                        "LIMIT "
                                                + size
                                                + " OFFSET "
                                                + (((long) current - 1) * size)));
        return new PageResponse<>(current, size, total, records.stream().map(this::view).toList());
    }

    /**
     * 获取当前用户拥有的文档响应。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param id 当前业务对象的唯一标识。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public DocumentBO get(KnowledgeBasePrincipal actor, UUID id) {
        return view(requireOwned(actor, id));
    }

    /**
     * 校验预期版本后更新文档元信息或处理配置，配置变更时推进修订。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param id 当前业务对象的唯一标识。
     * @param request 当前入口接收的请求参数。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @Transactional(timeout = 10)
    public DocumentBO patch(KnowledgeBasePrincipal actor, UUID id, DocumentPatchBO request) {
        // previous：本次修改前的记录或此前已经处理的值。
        var previous = requireOwned(actor, id);
        bases.lockOwned(actor, previous.getKbId());
        // doc：当前操作涉及的文档。
        var doc = mapper.selectOne(owned(actor).eq(DocumentDO::getId, id).last("FOR UPDATE"));
        if (doc == null)
            throw new KnowledgeBaseException(404, "KB_DOCUMENT_NOT_FOUND", "Document not found");
        if (!doc.getVersion().equals(request.version()))
            throw new KnowledgeBaseException(
                    409, "KB_VERSION_CONFLICT", "Reload the current version and retry");
        if (request.name() == null && request.ingestionSpec() == null)
            throw invalid("Provide name or ingestionSpec");
        if (request.name() != null) {
            // name：当前业务资源或策略的公开名称。
            String name = PreparedUpload.safeName(request.name());
            operations.checkName(doc.getKbId(), name, doc.getId());
            doc.setName(name);
        }
        if (request.ingestionSpec() != null) {
            checkParser(request.ingestionSpec(), doc.getOriginalFilename());
            parsers.resolve(
                    doc.getMimeType(),
                    request.ingestionSpec().parser(),
                    doc.getOriginalFilename(),
                    request.ingestionSpec().parseProfile());
            // PostgreSQL JSONB 会调整键顺序和空白，应比较解析后的值，避免把表示变化误判为配置变化。
            if (!request.ingestionSpec().equals(readSpec(doc.getIngestionSpec()))) {
                doc.setIngestionSpec(write(request.ingestionSpec()));
                doc.setConfigRevision(doc.getConfigRevision() + 1);
            }
        }
        doc.setVersion(doc.getVersion() + 1);
        doc.setUpdatedBy(actor.userId());
        doc.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        // 输入发生变化后旧执行快照失效，已经发布的旧代次继续提供检索。
        doc.setProcessStatus("PENDING");
        mapper.updateById(doc);
        return view(doc);
    }

    /**
     * 查找当前租户与用户拥有且未删除的文档。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param id 当前业务对象的唯一标识。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public DocumentDO requireOwned(KnowledgeBasePrincipal actor, UUID id) {
        // doc：当前操作涉及的文档。
        var doc = mapper.selectOne(owned(actor).eq(DocumentDO::getId, id));
        if (doc == null)
            throw new KnowledgeBaseException(404, "KB_DOCUMENT_NOT_FOUND", "Document not found");
        bases.requireOwned(actor, doc.getKbId());
        return doc;
    }

    /**
     * 锁定调用者拥有的文档，供后续事务变更使用。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param id 当前业务对象的唯一标识。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public DocumentDO lockOwned(KnowledgeBasePrincipal actor, UUID id) {
        // doc：当前操作涉及的文档。
        var doc = mapper.selectOne(owned(actor).eq(DocumentDO::getId, id).last("FOR UPDATE"));
        if (doc == null)
            throw new KnowledgeBaseException(404, "KB_DOCUMENT_NOT_FOUND", "Document not found");
        return doc;
    }

    /**
     * 构造文档查询的租户、所有者及未删除条件。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private LambdaQueryWrapper<DocumentDO> owned(KnowledgeBasePrincipal actor) {
        return new LambdaQueryWrapper<DocumentDO>()
                .eq(DocumentDO::getTenantId, actor.tenantId())
                .eq(DocumentDO::getOwnerId, actor.userId())
                .isNull(DocumentDO::getDeletedAt);
    }

    /**
     * 解析并校验上传元数据，缺省时补齐默认值。
     *
     * @param raw 尚未校验或规范化的原始输入。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private DocumentUploadBO metadata(String raw) {
        if (raw == null) return new DocumentUploadBO(null, null);
        if (raw.length() > 65536) throw invalid("Upload metadata is too large");
        try {
            // value：当前待校验、转换或写入的值。
            var value = json.readValue(raw, DocumentUploadBO.class);
            if (value == null || !validator.validate(value).isEmpty())
                throw invalid("Invalid upload metadata or ingestionSpec");
            if (value.name() != null) PreparedUpload.safeName(value.name());
            return value;
        } catch (JsonProcessingException e) {
            // e：本次捕获或匹配的异常，用于保留业务分类及失败原因。
            throw invalid("Invalid upload metadata or ingestionSpec");
        }
    }

    /**
     * 在上传前确认文件格式与所选解析器、档位兼容。
     *
     * @param spec 文档入库配置或当前切片策略参数。
     * @param filename 经过业务校验的文件名。
     */
    private static void checkParser(IngestionSpecBO spec, String filename) {
        // ext：规范化后的文件扩展名。
        String ext = SupportedFiles.extension(filename);
        if ((spec.parser().equals("TEXT") && !ext.equals("txt"))
                || (spec.parser().equals("MARKDOWN") && !Set.of("md", "markdown").contains(ext)))
            throw invalid(
                    "Explicit TEXT/MARKDOWN parser must match the file extension; use AUTO for other formats");
    }

    /**
     * 将处理配置序列化为持久化 JSON。
     *
     * @param value 当前待校验、转换或写入的值。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            // e：本次捕获或匹配的异常，用于保留业务分类及失败原因。
            throw new IllegalStateException("Cannot encode ingestion specification", e);
        }
    }

    /**
     * 还原数据库中的文档处理配置。
     *
     * @param value 当前待校验、转换或写入的值。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private IngestionSpecBO readSpec(String value) {
        try {
            return json.readValue(value, IngestionSpecBO.class);
        } catch (JsonProcessingException e) {
            // e：本次捕获或匹配的异常，用于保留业务分类及失败原因。
            throw new IllegalStateException("Invalid persisted ingestion specification", e);
        }
    }

    /**
     * 将文档实体及处理配置转换为对外响应。
     *
     * @param d 本次操作涉及的文档实体。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public DocumentBO view(DocumentDO d) {
        return new DocumentBO(
                d.getId(),
                d.getKbId(),
                d.getName(),
                d.getOriginalFilename(),
                d.getSourceType(),
                d.getObjectKey(),
                d.getMimeType(),
                d.getFileSize(),
                d.getContentHash(),
                d.getEnabled(),
                d.getProcessStatus(),
                readSpec(d.getIngestionSpec()),
                d.getConfigRevision(),
                d.getActiveGeneration(),
                d.getChunkCount(),
                d.getVersion(),
                d.getManualRevision(),
                d.getHasManualChanges(),
                d.getCreatedAt(),
                d.getUpdatedAt(),
                d.getSourceUrl(),
                d.getLastIngestedAt());
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
