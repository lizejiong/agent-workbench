package com.lzj.rag.backend.knowledgebase.controller;

import com.lzj.rag.backend.knowledgebase.controller.request.KnowledgeBaseBindRequest;
import com.lzj.rag.backend.knowledgebase.controller.request.KnowledgeBaseCreateRequest;
import com.lzj.rag.backend.knowledgebase.controller.request.KnowledgeBasePatchRequest;
import com.lzj.rag.backend.knowledgebase.controller.request.KnowledgeBaseUpdateRequest;
import com.lzj.rag.backend.knowledgebase.controller.vo.KnowledgeBaseVO;
import com.lzj.rag.backend.knowledgebase.converter.KnowledgebaseWebConverter;
import com.lzj.rag.backend.knowledgebase.service.KnowledgeBaseService;
import com.lzj.rag.backend.security.model.KnowledgeBasePrincipal;
import com.lzj.rag.framework.convention.page.PageResponse;
import com.lzj.rag.framework.convention.result.Result;
import com.lzj.rag.framework.starter.web.result.Results;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

/** 知识库 HTTP 入口，提供创建、查询、修改、删除和模型绑定能力。 */
@RestController
@RequestMapping({"/api/kbs", "/api/v1/knowledge-bases"})
public class KnowledgeBaseController {

    /** 负责知识库归属、版本与固定模型绑定的业务服务。 */
    private final KnowledgeBaseService service;

    /**
     * 初始化当前组件，保存业务依赖及本次使用的配置。
     *
     * @param service 负责知识库归属、版本与固定模型绑定的业务服务。
     */
    public KnowledgeBaseController(KnowledgeBaseService service) {
        this.service = service;
    }

    /**
     * 创建，将当前认证身份和接口参数交给对应业务服务处理。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param request 当前入口接收的请求参数。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @PostMapping
    public ResponseEntity<Result<KnowledgeBaseVO>> create(
            @AuthenticationPrincipal KnowledgeBasePrincipal actor,
            @Valid @RequestBody KnowledgeBaseCreateRequest request) {
        // view：当前业务记录转换得到的公开响应。
        var view =
                service.create(actor, KnowledgebaseWebConverter.toKnowledgeBaseCreateBO(request));
        return ResponseEntity.created(URI.create("/api/kbs/" + view.id()))
                .body(Results.success(KnowledgebaseWebConverter.toKnowledgeBaseVO(view)));
    }

    /**
     * 分页查询，将当前认证身份和接口参数交给对应业务服务处理。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param current 当前分页页码，从一开始。
     * @param size 分页容量或当前数据的字节数。
     * @param name 当前业务资源或策略的公开名称。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @GetMapping
    public Result<PageResponse<KnowledgeBaseVO>> list(
            @AuthenticationPrincipal KnowledgeBasePrincipal actor,
            @RequestParam(defaultValue = "1") @Min(1) @Max(1000000) int current,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "")
                    @Size(max = 128)
                    @jakarta.validation.constraints.Pattern(regexp = "[^\\x00]*")
                    String name) {
        // 集合按原顺序转换为接口响应。
        return Results.success(
                service.list(actor, current, size, name)
                        .convert(KnowledgebaseWebConverter::toKnowledgeBaseVO));
    }

    /**
     * 查询详情，将当前认证身份和接口参数交给对应业务服务处理。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param id 当前业务对象的唯一标识。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @GetMapping("/{id}")
    public Result<KnowledgeBaseVO> get(
            @AuthenticationPrincipal KnowledgeBasePrincipal actor, @PathVariable UUID id) {
        return Results.success(KnowledgebaseWebConverter.toKnowledgeBaseVO(service.get(actor, id)));
    }

    /**
     * 更新知识库信息，将当前认证身份和接口参数交给对应业务服务处理。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param id 当前业务对象的唯一标识。
     * @param request 当前入口接收的请求参数。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @PutMapping("/{id}")
    public Result<KnowledgeBaseVO> update(
            @AuthenticationPrincipal KnowledgeBasePrincipal actor,
            @PathVariable UUID id,
            @Valid @RequestBody KnowledgeBaseUpdateRequest request) {
        return Results.success(
                KnowledgebaseWebConverter.toKnowledgeBaseVO(
                        service.update(
                                actor,
                                id,
                                KnowledgebaseWebConverter.toKnowledgeBaseUpdateBO(request))));
    }

    /**
     * 删除，将当前认证身份和接口参数交给对应业务服务处理。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param id 当前业务对象的唯一标识。
     * @param version 当前记录版本或本次请求固定的版本标记。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(
            @AuthenticationPrincipal KnowledgeBasePrincipal actor,
            @PathVariable UUID id,
            @RequestParam @Min(1) long version) {
        service.delete(actor, id, version);
        return Results.success();
    }

    /**
     * 按预期版本修改，将当前认证身份和接口参数交给对应业务服务处理。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param id 当前业务对象的唯一标识。
     * @param request 当前入口接收的请求参数。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @PatchMapping("/{id}")
    public Result<KnowledgeBaseVO> patch(
            @AuthenticationPrincipal KnowledgeBasePrincipal actor,
            @PathVariable UUID id,
            @Valid @RequestBody KnowledgeBasePatchRequest request) {
        return Results.success(
                KnowledgebaseWebConverter.toKnowledgeBaseVO(
                        service.patch(
                                actor,
                                id,
                                KnowledgebaseWebConverter.toKnowledgeBasePatchBO(request))));
    }

    /**
     * 绑定嵌入模型配置，将当前认证身份和接口参数交给对应业务服务处理。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param id 当前业务对象的唯一标识。
     * @param request 当前入口接收的请求参数。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @PutMapping("/{id}/embedding-binding")
    public Result<KnowledgeBaseVO> bind(
            @AuthenticationPrincipal KnowledgeBasePrincipal actor,
            @PathVariable UUID id,
            @Valid @RequestBody KnowledgeBaseBindRequest request) {
        return Results.success(
                KnowledgebaseWebConverter.toKnowledgeBaseVO(
                        service.bindLegacy(
                                actor,
                                id,
                                KnowledgebaseWebConverter.toKnowledgeBaseBindBO(request))));
    }
}
