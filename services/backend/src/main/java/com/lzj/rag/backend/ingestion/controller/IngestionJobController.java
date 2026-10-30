package com.lzj.rag.backend.ingestion.controller;

import com.lzj.rag.backend.ingestion.controller.request.IngestionSubmitRequest;
import com.lzj.rag.backend.ingestion.controller.vo.IngestionJobVO;
import com.lzj.rag.backend.ingestion.controller.vo.IngestionStageVO;
import com.lzj.rag.backend.ingestion.converter.IngestionWebConverter;
import com.lzj.rag.backend.ingestion.service.IngestionJobService;
import com.lzj.rag.backend.ingestion.service.bo.IngestionSubmissionBO;
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

/** 入库任务提交、查询、重试和取消的 HTTP 入口。 */
@RestController
public class IngestionJobController {

    /** 负责持久化入库任务及租约状态迁移的业务服务。 */
    private final IngestionJobService jobs;

    /**
     * 初始化当前组件，保存业务依赖及本次使用的配置。
     *
     * @param jobs 负责持久化入库任务及租约状态迁移的业务服务。
     */
    public IngestionJobController(IngestionJobService jobs) {
        this.jobs = jobs;
    }

    /**
     * 提交入库任务，将当前认证身份和接口参数交给对应业务服务处理。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param documentId 文档唯一标识。
     * @param request 当前入口接收的请求参数。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @PostMapping("/api/documents/{documentId}/ingestion-jobs")
    public ResponseEntity<Result<IngestionJobVO>> submit(
            @AuthenticationPrincipal KnowledgeBasePrincipal actor,
            @PathVariable UUID documentId,
            @Valid @RequestBody IngestionSubmitRequest request) {
        return submitted(
                jobs.submit(
                        actor,
                        documentId,
                        request.requestId(),
                        null,
                        request.confirmOverwriteEdited(),
                        request.expectedDocumentVersion()));
    }

    /**
     * 查询详情，将当前认证身份和接口参数交给对应业务服务处理。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param jobId 持久化入库任务标识。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @GetMapping("/api/ingestion-jobs/{jobId}")
    public Result<IngestionJobVO> get(
            @AuthenticationPrincipal KnowledgeBasePrincipal actor, @PathVariable UUID jobId) {
        return Results.success(
                IngestionWebConverter.toIngestionJobVO(jobs.getOwnedJob(actor, jobId)));
    }

    /**
     * 分页查询任务阶段，将当前认证身份和接口参数交给对应业务服务处理。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param jobId 持久化入库任务标识。
     * @param current 当前分页页码，从一开始。
     * @param size 分页容量或当前数据的字节数。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @GetMapping("/api/ingestion-jobs/{jobId}/stages")
    public Result<PageResponse<IngestionStageVO>> stages(
            @AuthenticationPrincipal KnowledgeBasePrincipal actor,
            @PathVariable UUID jobId,
            @RequestParam(defaultValue = "1") @Min(1) @Max(1000000) int current,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        // job：当前入库任务记录或任务标识。
        var job = jobs.getOwnedJob(actor, jobId);
        // 集合按原顺序转换为接口响应。
        return Results.success(
                jobs.stages(actor, job.documentId(), jobId, current, size)
                        .convert(IngestionWebConverter::toIngestionStageVO));
    }

    /**
     * 重试失败操作，将当前认证身份和接口参数交给对应业务服务处理。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param jobId 持久化入库任务标识。
     * @param request 当前入口接收的请求参数。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @PostMapping("/api/ingestion-jobs/{jobId}/retry")
    public ResponseEntity<Result<IngestionJobVO>> retry(
            @AuthenticationPrincipal KnowledgeBasePrincipal actor,
            @PathVariable UUID jobId,
            @Valid @RequestBody IngestionSubmitRequest request) {
        // previous：本次修改前的记录或此前已经处理的值。
        var previous = jobs.getOwnedJob(actor, jobId);
        return submitted(
                jobs.submit(
                        actor,
                        previous.documentId(),
                        request.requestId(),
                        jobId,
                        request.confirmOverwriteEdited(),
                        request.expectedDocumentVersion()));
    }

    /**
     * 取消入库任务，将当前认证身份和接口参数交给对应业务服务处理。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param jobId 持久化入库任务标识。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @PostMapping("/api/ingestion-jobs/{jobId}/cancel")
    public Result<IngestionJobVO> cancel(
            @AuthenticationPrincipal KnowledgeBasePrincipal actor, @PathVariable UUID jobId) {
        return Results.success(IngestionWebConverter.toIngestionJobVO(jobs.cancel(actor, jobId)));
    }

    /**
     * 转换任务提交结果，将当前认证身份和接口参数交给对应业务服务处理。
     *
     * @param submission 持久化入库任务提交结果。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private ResponseEntity<Result<IngestionJobVO>> submitted(IngestionSubmissionBO submission) {
        return ResponseEntity.status(submission.replayed() ? 200 : 202)
                .location(URI.create("/api/ingestion-jobs/" + submission.job().id()))
                .header("Idempotency-Replayed", Boolean.toString(submission.replayed()))
                .body(Results.success(IngestionWebConverter.toIngestionJobVO(submission.job())));
    }
}
