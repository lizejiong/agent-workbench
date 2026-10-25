package com.lzj.rag.backend.document;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lzj.rag.backend.knowledgebase.*;
import com.lzj.rag.backend.model.EmbeddingCatalog;
import com.lzj.rag.backend.security.KnowledgeBasePrincipal;
import com.lzj.rag.backend.storage.*;
import com.lzj.rag.framework.convention.page.PageResponse;
import jakarta.validation.Validator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

import static com.lzj.rag.backend.document.DocumentTypes.*;

@Service
public class DocumentService {
    private final DocumentMapper mapper;
    private final KnowledgeBaseService bases;
    private final UploadOperations operations;
    private final ObjectStorageClient storage;
    private final StorageProperties properties;
    private final ObjectMapper json;
    private final Validator validator;
    private final com.lzj.rag.backend.parser.ParserRegistry parsers;

    public DocumentService(DocumentMapper mapper, KnowledgeBaseService bases, UploadOperations operations,
                           ObjectStorageClient storage, StorageProperties properties, ObjectMapper json, Validator validator,com.lzj.rag.backend.parser.ParserRegistry parsers) {
        this.mapper = mapper; this.bases = bases; this.operations = operations; this.storage = storage;
        this.properties = properties; this.json = json; this.validator = validator;
        this.parsers=parsers;
    }

    // Deliberately no @Transactional: staging and object I/O may be slow.
    public Uploaded upload(KnowledgeBasePrincipal actor, UUID kb, MultipartFile file, String rawMetadata, String key) {
        return uploadSource(actor,kb,file,rawMetadata,key,null,view->{ });
    }
    /** Shared validation/object compensation; callback participates in the document insertion transaction. */
    public Uploaded uploadSource(KnowledgeBasePrincipal actor, UUID kb, MultipartFile file, String rawMetadata, String key,
            String sourceUrl,java.util.function.Consumer<View> onCreated) {
        bases.requireOwned(actor, kb);
        if (!properties.enabled()) throw new KnowledgeBaseException(503, "KB_FEATURE_DISABLED", "Object storage is not enabled");
        if (key == null || !key.matches("[A-Za-z0-9_.:-]{16,128}")) throw invalid("Idempotency-Key must contain 16-128 safe ASCII characters");
        UploadMetadata metadata = metadata(rawMetadata);
        try (var prepared = PreparedUpload.prepare(file, properties)) {
            String name = metadata.name() == null ? prepared.filename : PreparedUpload.safeName(metadata.name());
            checkParser(metadata.ingestionSpec(), prepared.filename);
            parsers.resolve(prepared.mime,metadata.ingestionSpec().parser(),prepared.filename,metadata.ingestionSpec().parseProfile());
            String spec = write(metadata.ingestionSpec());
            String payload = EmbeddingCatalog.sha256(write(List.of(prepared.hash, prepared.filename, name, metadata.ingestionSpec())));
            var operation = operations.reserve(actor, kb, key, payload, name);
            if (operation.replayed()) return new Uploaded(view(requireOwned(actor, operation.id())), true);
            try {
                storage.put(operation.objectKey(), prepared.path, prepared.mime, prepared.hash);
                View result = operations.complete(actor, kb, operation, () -> {
                    operations.checkName(kb, name, null);
                    var doc = new Document();
                    doc.setId(operation.id()); doc.setKbId(kb); doc.setTenantId(actor.tenantId()); doc.setOwnerId(actor.userId());
                    doc.setName(name); doc.setOriginalFilename(prepared.filename); doc.setSourceType(sourceUrl==null?"UPLOAD":"URL");doc.setSourceUrl(sourceUrl);
                    doc.setObjectKey(operation.objectKey()); doc.setMimeType(prepared.mime); doc.setFileSize(prepared.size); doc.setContentHash(prepared.hash);
                    doc.setEnabled(true); doc.setProcessStatus("PENDING"); doc.setIngestionSpec(spec); doc.setConfigRevision(1L);
                    doc.setManualRevision(0L); doc.setHasManualChanges(false); doc.setChunkCount(0L); doc.setVersion(1L); doc.setCreatedBy(actor.userId()); doc.setUpdatedBy(actor.userId());
                    doc.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC)); doc.setUpdatedAt(doc.getCreatedAt());
                    mapper.insert(doc);
                    onCreated.accept(view(doc));
                    return view(doc);
                });
                return new Uploaded(result, false);
            } catch (RuntimeException error) {
                operations.compensate(actor, kb, operation.id());
                throw error;
            }
        }
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 10)
    public PageResponse<View> list(KnowledgeBasePrincipal actor, UUID kb, int current, int size, String name, String status) {
        bases.requireOwned(actor, kb);
        if (!status.isEmpty() && !Set.of("PENDING","PROCESSING","READY","FAILED").contains(status)) throw invalid("Unknown document process status");
        var filter = owned(actor).eq(Document::getKbId, kb);
        if (!name.isEmpty()) filter.apply("strpos(lower(name), lower({0})) > 0", name);
        if (!status.isEmpty()) filter.eq(Document::getProcessStatus, status);
        long total = mapper.selectCount(filter);
        var records = mapper.selectList(filter.orderByDesc(Document::getCreatedAt, Document::getId)
                .last("LIMIT " + size + " OFFSET " + (((long) current - 1) * size)));
        return new PageResponse<>(current, size, total, records.stream().map(this::view).toList());
    }
    public View get(KnowledgeBasePrincipal actor, UUID id) { return view(requireOwned(actor, id)); }

    @Transactional(timeout = 10)
    public View patch(KnowledgeBasePrincipal actor, UUID id, PatchRequest request) {
        var previous = requireOwned(actor, id);
        bases.lockOwned(actor, previous.getKbId());
        var doc = mapper.selectOne(owned(actor).eq(Document::getId, id).last("FOR UPDATE"));
        if (doc == null) throw new KnowledgeBaseException(404,"KB_DOCUMENT_NOT_FOUND","Document not found");
        if (!doc.getVersion().equals(request.version())) throw new KnowledgeBaseException(409, "KB_VERSION_CONFLICT", "Reload the current version and retry");
        if (request.name() == null && request.ingestionSpec() == null) throw invalid("Provide name or ingestionSpec");
        if (request.name() != null) {
            String name = PreparedUpload.safeName(request.name());
            operations.checkName(doc.getKbId(), name, doc.getId()); doc.setName(name);
        }
        if (request.ingestionSpec() != null) {
            checkParser(request.ingestionSpec(), doc.getOriginalFilename());
            parsers.resolve(doc.getMimeType(),request.ingestionSpec().parser(),doc.getOriginalFilename(),request.ingestionSpec().parseProfile());
            // Compare parsed values because PostgreSQL JSONB changes key order/whitespace.
            if (!request.ingestionSpec().equals(readSpec(doc.getIngestionSpec()))) {
                doc.setIngestionSpec(write(request.ingestionSpec())); doc.setConfigRevision(doc.getConfigRevision() + 1);
            }
        }
        doc.setVersion(doc.getVersion() + 1); doc.setUpdatedBy(actor.userId()); doc.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        doc.setProcessStatus("PENDING"); // Any changed input fences a running snapshot; old active generation remains readable.
        mapper.updateById(doc);
        return view(doc);
    }

    public Document requireOwned(KnowledgeBasePrincipal actor, UUID id) {
        var doc = mapper.selectOne(owned(actor).eq(Document::getId, id));
        if (doc == null) throw new KnowledgeBaseException(404, "KB_DOCUMENT_NOT_FOUND", "Document not found");
        bases.requireOwned(actor, doc.getKbId());
        return doc;
    }
    /** Fresh locking statement, distinct from cached nonlocking reads. Caller already holds the parent lock. */
    public Document lockOwned(KnowledgeBasePrincipal actor, UUID id) {
        var doc=mapper.selectOne(owned(actor).eq(Document::getId,id).last("FOR UPDATE"));
        if(doc==null)throw new KnowledgeBaseException(404,"KB_DOCUMENT_NOT_FOUND","Document not found");
        return doc;
    }
    private LambdaQueryWrapper<Document> owned(KnowledgeBasePrincipal actor) {
        return new LambdaQueryWrapper<Document>().eq(Document::getTenantId, actor.tenantId())
                .eq(Document::getOwnerId, actor.userId()).isNull(Document::getDeletedAt);
    }
    private UploadMetadata metadata(String raw) {
        if (raw == null) return new UploadMetadata(null, null);
        if (raw.length() > 65536) throw invalid("Upload metadata is too large");
        try {
            var value = json.readValue(raw, UploadMetadata.class);
            if (value == null || !validator.validate(value).isEmpty()) throw invalid("Invalid upload metadata or ingestionSpec");
            if (value.name() != null) PreparedUpload.safeName(value.name());
            return value;
        } catch (JsonProcessingException e) { throw invalid("Invalid upload metadata or ingestionSpec"); }
    }
    private static void checkParser(IngestionSpec spec, String filename) {
        String ext=com.lzj.rag.backend.parser.SupportedFiles.extension(filename);
        if ((spec.parser().equals("TEXT") && !ext.equals("txt")) ||
                (spec.parser().equals("MARKDOWN") && !Set.of("md","markdown").contains(ext)))
            throw invalid("Explicit TEXT/MARKDOWN parser must match the file extension; use AUTO for other formats");
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Cannot encode ingestion specification", e); }
    }
    private IngestionSpec readSpec(String value) {
        try { return json.readValue(value, IngestionSpec.class); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Invalid persisted ingestion specification", e); }
    }
    public View view(Document d) {
        return new View(d.getId(), d.getKbId(), d.getName(), d.getOriginalFilename(), d.getSourceType(), d.getObjectKey(),
                d.getMimeType(), d.getFileSize(), d.getContentHash(), d.getEnabled(), d.getProcessStatus(), readSpec(d.getIngestionSpec()),
                d.getConfigRevision(), d.getActiveGeneration(), d.getChunkCount(), d.getVersion(), d.getManualRevision(), d.getHasManualChanges(), d.getCreatedAt(), d.getUpdatedAt(),d.getSourceUrl(),d.getLastIngestedAt());
    }
    private static KnowledgeBaseException invalid(String message) { return new KnowledgeBaseException(400, "KB_INVALID_INPUT", message); }
}
