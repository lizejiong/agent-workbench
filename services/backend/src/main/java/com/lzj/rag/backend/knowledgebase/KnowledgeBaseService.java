package com.lzj.rag.backend.knowledgebase;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.lzj.rag.backend.security.KnowledgeBasePrincipal;
import com.lzj.rag.backend.model.EmbeddingCatalog;
import com.lzj.rag.framework.convention.page.PageResponse;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static com.lzj.rag.backend.knowledgebase.KnowledgeBaseTypes.*;

@Service
public class KnowledgeBaseService {
    private final KnowledgeBaseMapper mapper;
    private final EmbeddingCatalog catalog;
    private final JdbcTemplate jdbc;

    public KnowledgeBaseService(KnowledgeBaseMapper mapper, EmbeddingCatalog catalog, JdbcTemplate jdbc) {
        this.mapper = mapper; this.catalog = catalog; this.jdbc = jdbc;
    }

    @Transactional(timeout = 10)
    public View create(KnowledgeBasePrincipal actor, CreateRequest request) {
        var base = new KnowledgeBase();
        base.setId(UUID.randomUUID());
        base.setTenantId(actor.tenantId());
        base.setOwnerId(actor.userId());
        base.setName(normalizeName(request.name()));
        base.setDescription(request.description() == null ? "" : request.description());
        bind(base, catalog.resolve(request.embeddingProfileId()));
        base.setCreatedBy(actor.userId());
        base.setUpdatedBy(actor.userId());
        base.setVersion(1L);
        base.setCreatedAt(now());
        base.setUpdatedAt(base.getCreatedAt());
        mapper.insert(base);
        return View.from(base);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 10)
    public PageResponse<View> list(KnowledgeBasePrincipal actor, int current, int size) {
        return list(actor, current, size, "");
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 10)
    public PageResponse<View> list(KnowledgeBasePrincipal actor, int current, int size, String name) {
        var filter = owned(actor);
        if (!name.isEmpty()) filter.apply("strpos(lower(name), lower({0})) > 0", name);
        long total = mapper.selectCount(filter);
        var rows = mapper.selectOwnedPage(actor.tenantId(), actor.userId(), size, ((long) current - 1) * size, name);
        var counts=new java.util.HashMap<UUID,Long>();
        if(!rows.isEmpty())new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc).query(
                "SELECT kb_id,count(*) AS n FROM kb_document WHERE kb_id IN (:ids) AND tenant_id=:tenant AND owner_id=:owner AND deleted_at IS NULL GROUP BY kb_id",
                new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("ids",rows.stream().map(KnowledgeBase::getId).toList())
                        .addValue("tenant",actor.tenantId()).addValue("owner",actor.userId()),rs->{counts.put(rs.getObject("kb_id",UUID.class),rs.getLong("n"));});
        return new PageResponse<>(current, size, total, rows.stream().map(b->View.from(b,counts.getOrDefault(b.getId(),0L))).toList());
    }

    public View get(KnowledgeBasePrincipal actor, UUID id) {
        return view(requireOwned(actor, id));
    }

    @Transactional(timeout = 10)
    public View update(KnowledgeBasePrincipal actor, UUID id, UpdateRequest request) {
        requireOwned(actor, id);
        int changed = mapper.update(null, ownedVersion(actor, id, request.version())
                .set(KnowledgeBase::getName, normalizeName(request.name()))
                .set(KnowledgeBase::getDescription, request.description() == null ? "" : request.description())
                .set(KnowledgeBase::getUpdatedAt, now())
                .set(KnowledgeBase::getUpdatedBy, actor.userId())
                .setSql("version = version + 1"));
        requireChanged(changed);
        return view(requireOwned(actor, id));
    }

    @Transactional(timeout = 10)
    public void delete(KnowledgeBasePrincipal actor, UUID id, long version) {
        lockOwned(actor, id);
        if (Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM kb_document WHERE kb_id=? AND deleted_at IS NULL)
                    OR EXISTS(SELECT 1 FROM kb_upload_operation WHERE kb_id=? AND status IN ('UPLOADING','CLEANUP_PENDING'))
                    OR EXISTS(SELECT 1 FROM kb_document_cleanup WHERE kb_id=? AND status<>'SUCCEEDED')
                    OR EXISTS(SELECT 1 FROM kb_remote_object_cleanup WHERE kb_id=? AND status NOT IN ('RETAINED','SUCCEEDED'))
                    OR EXISTS(SELECT 1 FROM kb_url_import WHERE kb_id=? AND status='RUNNING' AND lease_until>clock_timestamp())
                    OR EXISTS(SELECT 1 FROM kb_keyword_sync WHERE kb_id=? AND indexed_target IS NOT NULL AND (status<>'SUCCEEDED' OR revision<>synced_revision))
                    OR EXISTS(SELECT 1 FROM kb_keyword_cleanup WHERE kb_id=? AND status<>'SUCCEEDED')
                """, Boolean.class, id, id, id,id,id,id,id))) {
            throw new KnowledgeBaseException(409, "KB_NOT_EMPTY", "Knowledge base contains documents or pending storage operations");
        }
        var deleted = now();
        int changed = mapper.update(null, ownedVersion(actor, id, version)
                .set(KnowledgeBase::getDeletedAt, deleted)
                .set(KnowledgeBase::getUpdatedAt, deleted)
                .set(KnowledgeBase::getUpdatedBy, actor.userId())
                .setSql("version = version + 1"));
        requireChanged(changed);
    }

    public KnowledgeBase requireOwned(KnowledgeBasePrincipal actor, UUID id) {
        var base = mapper.selectOne(owned(actor).eq(KnowledgeBase::getId, id));
        if (base == null) throw new KnowledgeBaseException(404, "KB_NOT_FOUND", "Knowledge base not found");
        return base;
    }

    /** Caller must hold a short database transaction; shared by upload/delete to prevent orphan inserts. */
    public KnowledgeBase lockOwned(KnowledgeBasePrincipal actor, UUID id) {
        var base = mapper.selectOne(owned(actor).eq(KnowledgeBase::getId, id).last("FOR UPDATE"));
        if (base == null) throw new KnowledgeBaseException(404, "KB_NOT_FOUND", "Knowledge base not found");
        return base;
    }

    @Transactional(timeout = 10)
    public View patch(KnowledgeBasePrincipal actor, UUID id, PatchRequest request) {
        var current = lockOwned(actor, id);
        if (request.embeddingProfileId() != null && !request.embeddingProfileId().equals(current.getEmbeddingProfileId())) {
            throw new KnowledgeBaseException(409, "KB_EMBEDDING_LOCKED", "Model binding is immutable; create a new knowledge base and rebuild to migrate");
        }
        if (request.name() == null && request.description() == null && request.embeddingProfileId() == null) {
            throw new KnowledgeBaseException(400, "KB_INVALID_INPUT", "Provide at least one metadata field");
        }
        return update(actor, id, new UpdateRequest(request.name() == null ? current.getName() : request.name(),
                request.description() == null ? current.getDescription() : request.description(), request.version()));
    }

    @Transactional(timeout = 10)
    public View bindLegacy(KnowledgeBasePrincipal actor, UUID id, BindRequest request) {
        var base = lockOwned(actor, id);
        if (base.getEmbeddingModelId() != null) throw new KnowledgeBaseException(409, "KB_EMBEDDING_LOCKED", "Knowledge base is already bound");
        if (!base.getVersion().equals(request.version())) requireChanged(0);
        bind(base, catalog.resolve(request.embeddingProfileId()));
        base.setUpdatedAt(now()); base.setUpdatedBy(actor.userId()); base.setVersion(base.getVersion() + 1);
        mapper.updateById(base);
        return view(base);
    }

    private View view(KnowledgeBase base) {
        return View.from(base,jdbc.queryForObject("SELECT count(*) FROM kb_document WHERE kb_id=? AND tenant_id=? AND owner_id=? AND deleted_at IS NULL",
                Long.class,base.getId(),base.getTenantId(),base.getOwnerId()));
    }

    private void bind(KnowledgeBase base, EmbeddingCatalog.Binding binding) {
        var profile = binding.profile();
        base.setEmbeddingProfileId(profile.id()); base.setEmbeddingModelId(profile.modelId());
        base.setEmbeddingProvider(profile.provider()); base.setEmbeddingDimension(profile.dimension());
        base.setEmbeddingConfigRevision(profile.configRevision()); base.setEmbeddingConfigJson(binding.snapshot());
        base.setVectorNamespace("kb_" + base.getId().toString().replace('-', '_'));
    }

    private LambdaQueryWrapper<KnowledgeBase> owned(KnowledgeBasePrincipal actor) {
        return new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getTenantId, actor.tenantId())
                .eq(KnowledgeBase::getOwnerId, actor.userId())
                .isNull(KnowledgeBase::getDeletedAt);
    }

    private LambdaUpdateWrapper<KnowledgeBase> ownedVersion(KnowledgeBasePrincipal actor, UUID id, long version) {
        return new LambdaUpdateWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getTenantId, actor.tenantId())
                .eq(KnowledgeBase::getOwnerId, actor.userId())
                .eq(KnowledgeBase::getId, id)
                .eq(KnowledgeBase::getVersion, version)
                .isNull(KnowledgeBase::getDeletedAt);
    }

    private void requireChanged(int changed) {
        if (changed != 1) throw new KnowledgeBaseException(409, "KB_VERSION_CONFLICT", "Reload the current version and retry");
    }

    private static String normalizeName(String name) {
        String normalized = name.strip();
        // Bean Validation's NotBlank may use trim(); strip also removes Unicode whitespace such as U+3000.
        if (normalized.isEmpty()) throw new KnowledgeBaseException(400, "KB_INVALID_INPUT", "Name must contain non-whitespace characters");
        return normalized;
    }

    private static OffsetDateTime now() { return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS); }
}
