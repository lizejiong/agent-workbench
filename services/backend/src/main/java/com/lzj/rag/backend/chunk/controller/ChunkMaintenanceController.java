package com.lzj.rag.backend.chunk.controller;

import com.lzj.rag.backend.chunk.controller.request.ChunkBatchRequest;
import com.lzj.rag.backend.chunk.controller.request.ChunkContentRequest;
import com.lzj.rag.backend.chunk.controller.request.ChunkEnabledRequest;
import com.lzj.rag.backend.chunk.controller.vo.ChunkChangedVO;
import com.lzj.rag.backend.chunk.controller.vo.ChunkViewVO;
import com.lzj.rag.backend.chunk.converter.ChunkWebConverter;
import com.lzj.rag.backend.chunk.service.ChunkMaintenanceService;
import com.lzj.rag.backend.document.controller.vo.DocumentCleanupVO;
import com.lzj.rag.backend.document.controller.vo.DocumentVO;
import com.lzj.rag.backend.document.converter.DocumentWebConverter;
import com.lzj.rag.backend.document.service.DocumentCleanupService;
import com.lzj.rag.backend.security.model.KnowledgeBasePrincipal;
import com.lzj.rag.framework.convention.page.PageResponse;
import com.lzj.rag.framework.convention.result.Result;
import com.lzj.rag.framework.starter.web.result.Results;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.*;

/** 切片维护 HTTP 入口，提供内容编辑、启停、删除和清理状态查询。 */
@RestController
public class ChunkMaintenanceController {

    /** 负责人工切片维护及索引一致性的业务服务。 */
    private final ChunkMaintenanceService service;

    /** 负责文档删除及持久化资源清理的业务服务。 */
    private final DocumentCleanupService cleanup;

    /**
     * 初始化当前组件，保存业务依赖及本次使用的配置。
     *
     * @param service 负责人工切片维护及索引一致性的业务服务。
     * @param cleanup 负责文档删除及持久化资源清理的业务服务。
     */
    public ChunkMaintenanceController(
            ChunkMaintenanceService service, DocumentCleanupService cleanup) {
        this.service = service;
        this.cleanup = cleanup;
    }

    /**
     * 分页查询，将当前认证身份和接口参数交给对应业务服务处理。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param documentId 文档唯一标识。
     * @param current 当前分页页码，从一开始。
     * @param size 分页容量或当前数据的字节数。
     * @param enabled 是否启用该资源或能力。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @GetMapping("/api/documents/{documentId}/chunks")
    public Result<PageResponse<ChunkViewVO>> list(
            @AuthenticationPrincipal KnowledgeBasePrincipal actor,
            @PathVariable UUID documentId,
            @RequestParam(defaultValue = "1") @Min(1) @Max(1000000) int current,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) Boolean enabled) {
        // 集合按原顺序转换为接口响应。
        return Results.success(
                service.list(actor, documentId, current, size, enabled)
                        .convert(ChunkWebConverter::toChunkViewVO));
    }

    /**
     * 创建，将当前认证身份和接口参数交给对应业务服务处理。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param documentId 文档唯一标识。
     * @param request 当前入口接收的请求参数。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @PostMapping("/api/documents/{documentId}/chunks")
    public ResponseEntity<Result<ChunkChangedVO>> create(
            @AuthenticationPrincipal KnowledgeBasePrincipal actor,
            @PathVariable UUID documentId,
            @Valid @RequestBody ChunkContentRequest request) {
        // result：当前步骤产生的处理结果。
        var result =
                service.save(actor, documentId, null, ChunkWebConverter.toChunkContentBO(request));
        return ResponseEntity.status(201)
                .body(Results.success(ChunkWebConverter.toChunkChangedVO(result)));
    }

    /**
     * 编辑，将当前认证身份和接口参数交给对应业务服务处理。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param documentId 文档唯一标识。
     * @param chunkId 切片唯一标识。
     * @param request 当前入口接收的请求参数。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @PatchMapping("/api/documents/{documentId}/chunks/{chunkId}")
    public Result<ChunkChangedVO> edit(
            @AuthenticationPrincipal KnowledgeBasePrincipal actor,
            @PathVariable UUID documentId,
            @PathVariable UUID chunkId,
            @Valid @RequestBody ChunkContentRequest request) {
        return Results.success(
                ChunkWebConverter.toChunkChangedVO(
                        service.save(
                                actor,
                                documentId,
                                chunkId,
                                ChunkWebConverter.toChunkContentBO(request))));
    }

    /**
     * 删除，将当前认证身份和接口参数交给对应业务服务处理。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param documentId 文档唯一标识。
     * @param chunkId 切片唯一标识。
     * @param version 当前记录版本或本次请求固定的版本标记。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @DeleteMapping("/api/documents/{documentId}/chunks/{chunkId}")
    public Result<ChunkChangedVO> delete(
            @AuthenticationPrincipal KnowledgeBasePrincipal actor,
            @PathVariable UUID documentId,
            @PathVariable UUID chunkId,
            @RequestParam @Positive long version) {
        return Results.success(
                ChunkWebConverter.toChunkChangedVO(
                        service.delete(actor, documentId, chunkId, version)));
    }

    /**
     * 切换启用状态，将当前认证身份和接口参数交给对应业务服务处理。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param documentId 文档唯一标识。
     * @param chunkId 切片唯一标识。
     * @param request 当前入口接收的请求参数。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @PatchMapping("/api/documents/{documentId}/chunks/{chunkId}/enabled")
    public Result<ChunkChangedVO> enabled(
            @AuthenticationPrincipal KnowledgeBasePrincipal actor,
            @PathVariable UUID documentId,
            @PathVariable UUID chunkId,
            @Valid @RequestBody ChunkEnabledRequest request) {
        return Results.success(
                ChunkWebConverter.toChunkChangedVO(
                        service.enabled(
                                actor,
                                documentId,
                                List.of(chunkId),
                                false,
                                request.enabled(),
                                request.version())));
    }

    /**
     * 批量修改切片启用状态，将当前认证身份和接口参数交给对应业务服务处理。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param documentId 文档唯一标识。
     * @param request 当前入口接收的请求参数。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @PostMapping("/api/documents/{documentId}/chunks/batch-enabled")
    public Result<ChunkChangedVO> batch(
            @AuthenticationPrincipal KnowledgeBasePrincipal actor,
            @PathVariable UUID documentId,
            @Valid @RequestBody ChunkBatchRequest request) {
        return Results.success(
                ChunkWebConverter.toChunkChangedVO(
                        service.enabled(
                                actor,
                                documentId,
                                request.chunkIds(),
                                request.applyAll(),
                                request.enabled(),
                                request.version())));
    }

    /**
     * 切换文档启用状态，将当前认证身份和接口参数交给对应业务服务处理。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param documentId 文档唯一标识。
     * @param request 当前入口接收的请求参数。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @PatchMapping("/api/documents/{documentId}/enabled")
    public Result<DocumentVO> documentEnabled(
            @AuthenticationPrincipal KnowledgeBasePrincipal actor,
            @PathVariable UUID documentId,
            @Valid @RequestBody ChunkEnabledRequest request) {
        return Results.success(
                DocumentWebConverter.toDocumentVO(
                        service.documentEnabled(
                                actor, documentId, request.enabled(), request.version())));
    }

    /**
     * 删除文档并提交清理任务，将当前认证身份和接口参数交给对应业务服务处理。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param documentId 文档唯一标识。
     * @param version 当前记录版本或本次请求固定的版本标记。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @DeleteMapping("/api/documents/{documentId}")
    public ResponseEntity<Result<DocumentCleanupVO>> documentDelete(
            @AuthenticationPrincipal KnowledgeBasePrincipal actor,
            @PathVariable UUID documentId,
            @RequestParam @Positive long version) {
        // result：当前步骤产生的处理结果。
        var result = cleanup.delete(actor, documentId, version);
        return ResponseEntity.accepted()
                .location(URI.create("/api/document-cleanups/" + result.id()))
                .body(Results.success(DocumentWebConverter.toDocumentCleanupVO(result)));
    }

    /**
     * 查询删除清理状态，将当前认证身份和接口参数交给对应业务服务处理。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param id 当前业务对象的唯一标识。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @GetMapping("/api/document-cleanups/{id}")
    public Result<DocumentCleanupVO> cleanup(
            @AuthenticationPrincipal KnowledgeBasePrincipal actor, @PathVariable UUID id) {
        return Results.success(DocumentWebConverter.toDocumentCleanupVO(cleanup.get(actor, id)));
    }

    /**
     * 重试失败操作，将当前认证身份和接口参数交给对应业务服务处理。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param id 当前业务对象的唯一标识。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @PostMapping("/api/document-cleanups/{id}/retry")
    public Result<DocumentCleanupVO> retry(
            @AuthenticationPrincipal KnowledgeBasePrincipal actor, @PathVariable UUID id) {
        return Results.success(DocumentWebConverter.toDocumentCleanupVO(cleanup.retry(actor, id)));
    }
}
