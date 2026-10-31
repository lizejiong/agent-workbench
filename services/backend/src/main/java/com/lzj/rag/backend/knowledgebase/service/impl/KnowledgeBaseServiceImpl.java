package com.lzj.rag.backend.knowledgebase.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.lzj.rag.backend.knowledgebase.converter.KnowledgeBaseDataConverter;
import com.lzj.rag.backend.knowledgebase.dao.entity.KnowledgeBaseDO;
import com.lzj.rag.backend.knowledgebase.dao.mapper.KnowledgeBaseMapper;
import com.lzj.rag.backend.knowledgebase.exception.KnowledgeBaseException;
import com.lzj.rag.backend.knowledgebase.service.KnowledgeBaseService;
import com.lzj.rag.backend.knowledgebase.service.bo.KnowledgeBaseBO;
import com.lzj.rag.backend.knowledgebase.service.bo.KnowledgeBaseBindBO;
import com.lzj.rag.backend.knowledgebase.service.bo.KnowledgeBaseCreateBO;
import com.lzj.rag.backend.knowledgebase.service.bo.KnowledgeBasePatchBO;
import com.lzj.rag.backend.knowledgebase.service.bo.KnowledgeBaseUpdateBO;
import com.lzj.rag.backend.knowledgebase.service.internal.KnowledgeBaseAccess;
import com.lzj.rag.backend.model.catalog.EmbeddingCatalog;
import com.lzj.rag.backend.security.model.KnowledgeBasePrincipal;
import com.lzj.rag.framework.convention.page.PageResponse;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** 管理知识库及不可变嵌入绑定，统一执行归属、版本和并发变更校验。 */
@Service
public class KnowledgeBaseServiceImpl implements KnowledgeBaseService, KnowledgeBaseAccess {

    @Override
    public java.util.List<UUID> searchableIds(KnowledgeBasePrincipal actor, int limit) {
        if (limit < 1 || limit > 65) throw new IllegalArgumentException("Invalid scope limit");
        return jdbc.query("SELECT id FROM kb_knowledge_base WHERE tenant_id=? AND owner_id=? "
                        + "AND deleted_at IS NULL ORDER BY id LIMIT ?",
                (rs, row) -> rs.getObject(1, UUID.class), actor.tenantId(), actor.userId(), limit);
    }

    /** 知识库实体数据访问接口。 */
    private final KnowledgeBaseMapper mapper;

    /** 已校验的嵌入配置目录。 */
    private final EmbeddingCatalog catalog;

    /** 执行数据库查询及受条件保护的业务状态更新。 */
    private final JdbcTemplate jdbc;

    /**
     * 初始化当前组件，保存业务依赖及本次使用的配置。
     *
     * @param mapper 知识库实体数据访问接口。
     * @param catalog 已校验的嵌入配置目录。
     * @param jdbc 执行数据库查询及受条件保护的业务状态更新。
     */
    public KnowledgeBaseServiceImpl(
            KnowledgeBaseMapper mapper, EmbeddingCatalog catalog, JdbcTemplate jdbc) {
        this.mapper = mapper;
        this.catalog = catalog;
        this.jdbc = jdbc;
    }

    /**
     * 规范化名称并校验可选模型绑定，创建属于当前用户的知识库。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param request 当前入口接收的请求参数。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @Transactional(timeout = 10)
    public KnowledgeBaseBO create(KnowledgeBasePrincipal actor, KnowledgeBaseCreateBO request) {
        // base：当前操作涉及的知识库及其固定模型绑定。
        var base = new KnowledgeBaseDO();
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
        return KnowledgeBaseDataConverter.from(base);
    }

    /**
     * 在租户与所有者范围内分页查询知识库，可按名称筛选。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param current 当前分页页码，从一开始。
     * @param size 分页容量或当前数据的字节数。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 10)
    public PageResponse<KnowledgeBaseBO> list(KnowledgeBasePrincipal actor, int current, int size) {
        return list(actor, current, size, "");
    }

    /**
     * 在租户与所有者范围内分页查询知识库，可按名称筛选。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param current 当前分页页码，从一开始。
     * @param size 分页容量或当前数据的字节数。
     * @param name 当前业务资源或策略的公开名称。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 10)
    public PageResponse<KnowledgeBaseBO> list(
            KnowledgeBasePrincipal actor, int current, int size, String name) {
        // filter：包含租户、所有者及当前业务筛选条件的查询约束。
        var filter = owned(actor);
        if (!name.isEmpty()) filter.apply("strpos(lower(name), lower({0})) > 0", name);
        // total：满足当前查询条件的记录总数。
        long total = mapper.selectCount(filter);
        // rows：数据库结果或当前表格数据行集合。
        var rows =
                mapper.selectOwnedPage(
                        actor.tenantId(), actor.userId(), size, ((long) current - 1) * size, name);
        // counts：从数据库加载的分组计数结果。
        var counts = new java.util.HashMap<UUID, Long>();
        if (!rows.isEmpty())
            new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc)
                    .query(
                            "SELECT kb_id,count(*) AS n FROM kb_document WHERE kb_id IN (:ids) AND tenant_id=:tenant AND owner_id=:owner AND deleted_at IS NULL GROUP BY kb_id",
                            new org.springframework.jdbc.core.namedparam.MapSqlParameterSource(
                                            "ids",
                                            rows.stream().map(KnowledgeBaseDO::getId).toList())
                                    .addValue("tenant", actor.tenantId())
                                    .addValue("owner", actor.userId()),
                            rs -> {
                                // rs：数据库查询的当前结果行。
                                counts.put(rs.getObject("kb_id", UUID.class), rs.getLong("n"));
                            });
        // b：当前结构块或输入缓冲。
        return new PageResponse<>(
                current,
                size,
                total,
                rows.stream()
                        .map(
                                b ->
                                        KnowledgeBaseDataConverter.from(
                                                b, counts.getOrDefault(b.getId(), 0L)))
                        .toList());
    }

    /**
     * 返回当前用户拥有的知识库及公开模型绑定信息。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param id 当前业务对象的唯一标识。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public KnowledgeBaseBO get(KnowledgeBasePrincipal actor, UUID id) {
        return view(requireOwned(actor, id));
    }

    /**
     * 按预期版本更新知识库信息，拒绝修改已有内容的固定模型身份。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param id 当前业务对象的唯一标识。
     * @param request 当前入口接收的请求参数。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @Transactional(timeout = 10)
    public KnowledgeBaseBO update(
            KnowledgeBasePrincipal actor, UUID id, KnowledgeBaseUpdateBO request) {
        requireOwned(actor, id);
        // changed：输入版本是否发生变化，或条件更新实际影响的行数。
        int changed =
                mapper.update(
                        null,
                        ownedVersion(actor, id, request.version())
                                .set(KnowledgeBaseDO::getName, normalizeName(request.name()))
                                .set(
                                        KnowledgeBaseDO::getDescription,
                                        request.description() == null ? "" : request.description())
                                .set(KnowledgeBaseDO::getUpdatedAt, now())
                                .set(KnowledgeBaseDO::getUpdatedBy, actor.userId())
                                .setSql("version = version + 1"));
        requireChanged(changed);
        return view(requireOwned(actor, id));
    }

    /**
     * 校验版本及关联内容约束后删除知识库。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param id 当前业务对象的唯一标识。
     * @param version 当前记录版本或本次请求固定的版本标记。
     */
    @Transactional(timeout = 10)
    public void delete(KnowledgeBasePrincipal actor, UUID id, long version) {
        lockOwned(actor, id);
        if (Boolean.TRUE.equals(
                jdbc.queryForObject(
                        """
            SELECT EXISTS(SELECT 1 FROM kb_document WHERE kb_id=? AND deleted_at IS NULL)
                OR EXISTS(SELECT 1 FROM kb_upload_operation WHERE kb_id=? AND status IN ('UPLOADING','CLEANUP_PENDING'))
                OR EXISTS(SELECT 1 FROM kb_document_cleanup WHERE kb_id=? AND status<>'SUCCEEDED')
                OR EXISTS(SELECT 1 FROM kb_remote_object_cleanup WHERE kb_id=? AND status NOT IN ('RETAINED','SUCCEEDED'))
                OR EXISTS(SELECT 1 FROM kb_url_import WHERE kb_id=? AND status='RUNNING' AND lease_until>clock_timestamp())
                OR EXISTS(SELECT 1 FROM kb_keyword_sync WHERE kb_id=? AND indexed_target IS NOT NULL AND (status<>'SUCCEEDED' OR revision<>synced_revision))
                OR EXISTS(SELECT 1 FROM kb_keyword_cleanup WHERE kb_id=? AND status<>'SUCCEEDED')
            """,
                        Boolean.class,
                        id,
                        id,
                        id,
                        id,
                        id,
                        id,
                        id))) {
            throw new KnowledgeBaseException(
                    409,
                    "KB_NOT_EMPTY",
                    "Knowledge base contains documents or pending storage operations");
        }
        var deleted = now();
        // changed：输入版本是否发生变化，或条件更新实际影响的行数。
        int changed =
                mapper.update(
                        null,
                        ownedVersion(actor, id, version)
                                .set(KnowledgeBaseDO::getDeletedAt, deleted)
                                .set(KnowledgeBaseDO::getUpdatedAt, deleted)
                                .set(KnowledgeBaseDO::getUpdatedBy, actor.userId())
                                .setSql("version = version + 1"));
        requireChanged(changed);
    }

    /**
     * 查找当前租户与用户拥有且未删除的知识库。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param id 当前业务对象的唯一标识。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @Override
    public KnowledgeBaseDO requireOwned(KnowledgeBasePrincipal actor, UUID id) {
        // base：当前操作涉及的知识库及其固定模型绑定。
        var base = mapper.selectOne(owned(actor).eq(KnowledgeBaseDO::getId, id));
        if (base == null)
            throw new KnowledgeBaseException(404, "KB_NOT_FOUND", "Knowledge base not found");
        return base;
    }

    /**
     * 按归属条件锁定知识库，作为文档与任务变更的首个业务锁。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param id 当前业务对象的唯一标识。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @Override
    public KnowledgeBaseDO lockOwned(KnowledgeBasePrincipal actor, UUID id) {
        // base：当前操作涉及的知识库及其固定模型绑定。
        var base = mapper.selectOne(owned(actor).eq(KnowledgeBaseDO::getId, id).last("FOR UPDATE"));
        if (base == null)
            throw new KnowledgeBaseException(404, "KB_NOT_FOUND", "Knowledge base not found");
        return base;
    }

    /**
     * 在锁内按预期版本局部更新知识库信息与启用状态。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param id 当前业务对象的唯一标识。
     * @param request 当前入口接收的请求参数。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @Transactional(timeout = 10)
    public KnowledgeBaseBO patch(
            KnowledgeBasePrincipal actor, UUID id, KnowledgeBasePatchBO request) {
        var current = lockOwned(actor, id);
        if (request.embeddingProfileId() != null
                && !request.embeddingProfileId().equals(current.getEmbeddingProfileId())) {
            throw new KnowledgeBaseException(
                    409,
                    "KB_EMBEDDING_LOCKED",
                    "Model binding is immutable; create a new knowledge base and rebuild to migrate");
        }
        if (request.name() == null
                && request.description() == null
                && request.embeddingProfileId() == null) {
            throw new KnowledgeBaseException(
                    400, "KB_INVALID_INPUT", "Provide at least one metadata field");
        }
        return update(
                actor,
                id,
                new KnowledgeBaseUpdateBO(
                        request.name() == null ? current.getName() : request.name(),
                        request.description() == null
                                ? current.getDescription()
                                : request.description(),
                        request.version()));
    }

    /**
     * 为未绑定模型的知识库补充固定嵌入配置，并推进版本。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param id 当前业务对象的唯一标识。
     * @param request 当前入口接收的请求参数。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @Transactional(timeout = 10)
    public KnowledgeBaseBO bindLegacy(
            KnowledgeBasePrincipal actor, UUID id, KnowledgeBaseBindBO request) {
        // base：当前操作涉及的知识库及其固定模型绑定。
        var base = lockOwned(actor, id);
        if (base.getEmbeddingModelId() != null)
            throw new KnowledgeBaseException(
                    409, "KB_EMBEDDING_LOCKED", "Knowledge base is already bound");
        if (!base.getVersion().equals(request.version())) requireChanged(0);
        bind(base, catalog.resolve(request.embeddingProfileId()));
        base.setUpdatedAt(now());
        base.setUpdatedBy(actor.userId());
        base.setVersion(base.getVersion() + 1);
        mapper.updateById(base);
        return view(base);
    }

    /**
     * 将知识库实体转换为公开查询响应。
     *
     * @param base 本次操作涉及的知识库实体。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private KnowledgeBaseBO view(KnowledgeBaseDO base) {
        return KnowledgeBaseDataConverter.from(
                base,
                jdbc.queryForObject(
                        "SELECT count(*) FROM kb_document WHERE kb_id=? AND tenant_id=? AND owner_id=? AND deleted_at IS NULL",
                        Long.class,
                        base.getId(),
                        base.getTenantId(),
                        base.getOwnerId()));
    }

    /**
     * 将已校验模型绑定的身份、维度和配置快照写入知识库实体。
     *
     * @param base 本次操作涉及的知识库实体。
     * @param binding 本次业务固定使用的嵌入模型身份与配置快照。
     */
    private void bind(KnowledgeBaseDO base, EmbeddingCatalog.Binding binding) {
        // profile：公开配置或解析策略标识。
        var profile = binding.profile();
        base.setEmbeddingProfileId(profile.id());
        base.setEmbeddingModelId(profile.modelId());
        base.setEmbeddingProvider(profile.provider());
        base.setEmbeddingDimension(profile.dimension());
        base.setEmbeddingConfigRevision(profile.configRevision());
        base.setEmbeddingConfigJson(binding.snapshot());
        base.setVectorNamespace("kb_" + base.getId().toString().replace('-', '_'));
    }

    /**
     * 构造包含租户、所有者和未删除条件的知识库查询。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private LambdaQueryWrapper<KnowledgeBaseDO> owned(KnowledgeBasePrincipal actor) {
        return new LambdaQueryWrapper<KnowledgeBaseDO>()
                .eq(KnowledgeBaseDO::getTenantId, actor.tenantId())
                .eq(KnowledgeBaseDO::getOwnerId, actor.userId())
                .isNull(KnowledgeBaseDO::getDeletedAt);
    }

    /**
     * 在归属条件上增加知识库标识和乐观锁版本约束。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param id 当前业务对象的唯一标识。
     * @param version 当前记录版本或本次请求固定的版本标记。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private LambdaUpdateWrapper<KnowledgeBaseDO> ownedVersion(
            KnowledgeBasePrincipal actor, UUID id, long version) {
        return new LambdaUpdateWrapper<KnowledgeBaseDO>()
                .eq(KnowledgeBaseDO::getTenantId, actor.tenantId())
                .eq(KnowledgeBaseDO::getOwnerId, actor.userId())
                .eq(KnowledgeBaseDO::getId, id)
                .eq(KnowledgeBaseDO::getVersion, version)
                .isNull(KnowledgeBaseDO::getDeletedAt);
    }

    /**
     * 确认条件更新恰好修改一条记录，否则报告版本冲突。
     *
     * @param changed 输入版本是否发生变化，或条件更新实际影响的行数。
     */
    private void requireChanged(int changed) {
        if (changed != 1)
            throw new KnowledgeBaseException(
                    409, "KB_VERSION_CONFLICT", "Reload the current version and retry");
    }

    /**
     * 去除名称两端空白并检查业务长度与合法性。
     *
     * @param name 当前业务资源或策略的公开名称。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private static String normalizeName(String name) {
        // normalized：经过规范化处理的名称或文本。
        String normalized = name.strip();
        // NotBlank 可能按 trim 处理空白；strip 还会清除 U+3000 等 Unicode 空白。
        if (normalized.isEmpty())
            throw new KnowledgeBaseException(
                    400, "KB_INVALID_INPUT", "Name must contain non-whitespace characters");
        return normalized;
    }

    /**
     * 返回知识库业务记录使用的当前时间。
     *
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }
}
