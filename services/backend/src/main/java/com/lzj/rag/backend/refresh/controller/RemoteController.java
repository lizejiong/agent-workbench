package com.lzj.rag.backend.refresh.controller;

import com.lzj.rag.backend.refresh.controller.request.RefreshRequest;
import com.lzj.rag.backend.refresh.controller.request.RefreshScheduleRequest;
import com.lzj.rag.backend.refresh.controller.request.RemoteImportRequest;
import com.lzj.rag.backend.refresh.controller.vo.RefreshRunVO;
import com.lzj.rag.backend.refresh.controller.vo.RefreshScheduleResultVO;
import com.lzj.rag.backend.refresh.controller.vo.RemoteImportedVO;
import com.lzj.rag.backend.refresh.converter.RefreshWebConverter;
import com.lzj.rag.backend.refresh.importing.UrlImportService;
import com.lzj.rag.backend.refresh.service.RefreshService;
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
import java.util.UUID;

/** URL 导入、刷新计划和运行记录的 HTTP 入口。 */
@RestController
public class RemoteController {

    /** 负责远程 URL 文档幂等导入的业务服务。 */
    private final UrlImportService imports;

    /** 负责刷新计划、运行领取及条件下载的业务服务。 */
    private final RefreshService refresh;

    /**
     * 初始化当前组件，保存业务依赖及本次使用的配置。
     *
     * @param imports 负责远程 URL 文档幂等导入的业务服务。
     * @param refresh 负责刷新计划、运行领取及条件下载的业务服务。
     */
    public RemoteController(UrlImportService imports, RefreshService refresh) {
        this.imports = imports;
        this.refresh = refresh;
    }

    /**
     * 导入远程 URL 文档，将当前认证身份和接口参数交给对应业务服务处理。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param kbId 知识库唯一标识。
     * @param key 对象存储键、配置键或业务幂等键。
     * @param request 当前入口接收的请求参数。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @PostMapping("/api/kbs/{kbId}/documents/import-url")
    public ResponseEntity<Result<RemoteImportedVO>> importUrl(
            @AuthenticationPrincipal KnowledgeBasePrincipal actor,
            @PathVariable UUID kbId,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody RemoteImportRequest request) {
        // result：当前步骤产生的处理结果。
        var result =
                imports.importUrl(actor, kbId, key, RefreshWebConverter.toRemoteImportBO(request));
        return ResponseEntity.status(result.replayed() ? 200 : request.ingest() ? 202 : 201)
                .location(URI.create("/api/documents/" + result.document().id()))
                .header("Idempotency-Replayed", Boolean.toString(result.replayed()))
                .body(Results.success(RefreshWebConverter.toRemoteImportedVO(result)));
    }

    /**
     * 读取或修改刷新计划，将当前认证身份和接口参数交给对应业务服务处理。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param id 当前业务对象的唯一标识。
     * @param request 当前入口接收的请求参数。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @PutMapping("/api/documents/{id}/refresh-schedule")
    public Result<RefreshScheduleResultVO> schedule(
            @AuthenticationPrincipal KnowledgeBasePrincipal actor,
            @PathVariable UUID id,
            @Valid @RequestBody RefreshScheduleRequest request) {
        return Results.success(
                RefreshWebConverter.toRefreshScheduleResultVO(
                        refresh.configure(
                                actor, id, RefreshWebConverter.toRefreshScheduleBO(request))));
    }

    /**
     * 读取或修改刷新计划，将当前认证身份和接口参数交给对应业务服务处理。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param id 当前业务对象的唯一标识。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @GetMapping("/api/documents/{id}/refresh-schedule")
    public Result<RefreshScheduleResultVO> schedule(
            @AuthenticationPrincipal KnowledgeBasePrincipal actor, @PathVariable UUID id) {
        return Results.success(
                RefreshWebConverter.toRefreshScheduleResultVO(refresh.schedule(actor, id)));
    }

    /**
     * 提交手动刷新，将当前认证身份和接口参数交给对应业务服务处理。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param id 当前业务对象的唯一标识。
     * @param request 当前入口接收的请求参数。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @PostMapping("/api/documents/{id}/refresh")
    public ResponseEntity<Result<RefreshRunVO>> refresh(
            @AuthenticationPrincipal KnowledgeBasePrincipal actor,
            @PathVariable UUID id,
            @Valid @RequestBody RefreshRequest request) {
        // result：当前步骤产生的处理结果。
        var result = refresh.submit(actor, id, RefreshWebConverter.toRefreshBO(request));
        return ResponseEntity.status(result.replayed() ? 200 : 202)
                .header("Idempotency-Replayed", Boolean.toString(result.replayed()))
                .body(Results.success(RefreshWebConverter.toRefreshRunVO(result.run())));
    }

    /**
     * 分页查询刷新记录，将当前认证身份和接口参数交给对应业务服务处理。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param id 当前业务对象的唯一标识。
     * @param current 当前分页页码，从一开始。
     * @param size 分页容量或当前数据的字节数。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @GetMapping("/api/documents/{id}/refresh-runs")
    public Result<PageResponse<RefreshRunVO>> runs(
            @AuthenticationPrincipal KnowledgeBasePrincipal actor,
            @PathVariable UUID id,
            @RequestParam(defaultValue = "1") @Min(1) @Max(1000000) int current,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        // 集合按原顺序转换为接口响应。
        return Results.success(
                refresh.list(actor, id, current, size)
                        .convert(RefreshWebConverter::toRefreshRunVO));
    }
}
