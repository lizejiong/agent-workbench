package com.lzj.rag.backend.document.controller;

import com.lzj.rag.backend.document.controller.request.DocumentPatchRequest;
import com.lzj.rag.backend.document.controller.vo.DocumentVO;
import com.lzj.rag.backend.document.controller.vo.UploadOperationResultVO;
import com.lzj.rag.backend.document.converter.DocumentWebConverter;
import com.lzj.rag.backend.document.service.DocumentService;
import com.lzj.rag.backend.document.upload.UploadOperations;
import com.lzj.rag.backend.security.model.KnowledgeBasePrincipal;
import com.lzj.rag.framework.convention.page.PageResponse;
import com.lzj.rag.framework.convention.result.Result;
import com.lzj.rag.framework.starter.web.result.Results;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** 文档 HTTP 入口，处理上传、分页、元数据修改及授权源文件读取。 */
@RestController
public class DocumentController {

    /** 负责文档归属、上传和配置维护的业务服务。 */
    private final DocumentService service;

    /** 负责上传预留和失败对象补偿的业务组件。 */
    private final UploadOperations operations;

    /**
     * 初始化当前组件，保存业务依赖及本次使用的配置。
     *
     * @param service 负责文档归属、上传和配置维护的业务服务。
     * @param operations 负责上传预留和失败对象补偿的业务组件。
     */
    public DocumentController(DocumentService service, UploadOperations operations) {
        this.service = service;
        this.operations = operations;
    }

    /**
     * 上传文档，将当前认证身份和接口参数交给对应业务服务处理。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param kbId 知识库唯一标识。
     * @param file 调用方上传的文件输入，后续按流读取并检查大小及格式。
     * @param metadata 上传业务元数据或切片来源定位字段。
     * @param idempotencyKey 防止重复提交的业务幂等键。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @PostMapping(
            value = "/api/kbs/{kbId}/documents",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Result<DocumentVO>> upload(
            @AuthenticationPrincipal KnowledgeBasePrincipal actor,
            @PathVariable UUID kbId,
            @RequestPart MultipartFile file,
            @RequestPart(required = false) String metadata,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        // uploaded：已经完成对象写入及文档创建的上传结果。
        var uploaded =
                service.upload(
                        actor, kbId, DocumentWebConverter.toBO(file), metadata, idempotencyKey);
        return ResponseEntity.status(uploaded.replayed() ? 200 : 201)
                .location(URI.create("/api/documents/" + uploaded.document().id()))
                .header("Idempotency-Replayed", Boolean.toString(uploaded.replayed()))
                .body(Results.success(DocumentWebConverter.toDocumentVO(uploaded.document())));
    }

    /**
     * 分页查询，将当前认证身份和接口参数交给对应业务服务处理。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param kbId 知识库唯一标识。
     * @param current 当前分页页码，从一开始。
     * @param size 分页容量或当前数据的字节数。
     * @param name 当前业务资源或策略的公开名称。
     * @param status 当前业务操作或任务状态。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @GetMapping("/api/kbs/{kbId}/documents")
    public Result<PageResponse<DocumentVO>> list(
            @AuthenticationPrincipal KnowledgeBasePrincipal actor,
            @PathVariable UUID kbId,
            @RequestParam(defaultValue = "1") @Min(1) @Max(1000000) int current,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "") @Size(max = 255) @Pattern(regexp = "[^\\x00]*")
                    String name,
            @RequestParam(defaultValue = "") @Size(max = 32) String status) {
        // 集合按原顺序转换为接口响应。
        return Results.success(
                service.list(actor, kbId, current, size, name, status)
                        .convert(DocumentWebConverter::toDocumentVO));
    }

    /**
     * 查询详情，将当前认证身份和接口参数交给对应业务服务处理。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param id 当前业务对象的唯一标识。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @GetMapping("/api/documents/{id}")
    public Result<DocumentVO> get(
            @AuthenticationPrincipal KnowledgeBasePrincipal actor, @PathVariable UUID id) {
        return Results.success(DocumentWebConverter.toDocumentVO(service.get(actor, id)));
    }

    /**
     * 按预期版本修改，将当前认证身份和接口参数交给对应业务服务处理。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param id 当前业务对象的唯一标识。
     * @param request 当前入口接收的请求参数。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @PatchMapping("/api/documents/{id}")
    public Result<DocumentVO> patch(
            @AuthenticationPrincipal KnowledgeBasePrincipal actor,
            @PathVariable UUID id,
            @Valid @RequestBody DocumentPatchRequest request) {
        return Results.success(
                DocumentWebConverter.toDocumentVO(
                        service.patch(actor, id, DocumentWebConverter.toDocumentPatchBO(request))));
    }

    /**
     * 授权读取文档源文件，将当前认证身份和接口参数交给对应业务服务处理。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param id 当前业务对象的唯一标识。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @GetMapping("/api/documents/{id}/file")
    public ResponseEntity<InputStreamResource> file(
            @AuthenticationPrincipal KnowledgeBasePrincipal actor, @PathVariable UUID id) {
        var file = service.openFile(actor, id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.mimeType()))
                .contentLength(file.fileSize())
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(file.originalFilename(), StandardCharsets.UTF_8)
                                .build()
                                .toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-Type-Options", "nosniff")
                // Spring 在发送完成或发送失败后关闭文件流。
                .body(new InputStreamResource(file.content()));
    }

    /**
     * 分页查询上传及补偿操作，将当前认证身份和接口参数交给对应业务服务处理。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param kbId 知识库唯一标识。
     * @param current 当前分页页码，从一开始。
     * @param size 分页容量或当前数据的字节数。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @GetMapping("/api/kbs/{kbId}/storage-operations")
    public Result<PageResponse<UploadOperationResultVO>> operations(
            @AuthenticationPrincipal KnowledgeBasePrincipal actor,
            @PathVariable UUID kbId,
            @RequestParam(defaultValue = "1") @Min(1) @Max(1000000) int current,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        // 集合按原顺序转换为接口响应。
        return Results.success(
                operations
                        .list(actor, kbId, current, size)
                        .convert(DocumentWebConverter::toUploadOperationResultVO));
    }

    /**
     * 重试失败操作，将当前认证身份和接口参数交给对应业务服务处理。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param kbId 知识库唯一标识。
     * @param id 当前业务对象的唯一标识。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @PostMapping("/api/kbs/{kbId}/storage-operations/{id}/retry-cleanup")
    public Result<UploadOperationResultVO> retry(
            @AuthenticationPrincipal KnowledgeBasePrincipal actor,
            @PathVariable UUID kbId,
            @PathVariable UUID id) {
        return Results.success(
                DocumentWebConverter.toUploadOperationResultVO(
                        operations.retryCleanup(actor, kbId, id)));
    }
}
