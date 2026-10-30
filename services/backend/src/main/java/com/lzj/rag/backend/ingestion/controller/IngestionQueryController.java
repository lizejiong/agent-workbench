package com.lzj.rag.backend.ingestion.controller;

import com.lzj.rag.backend.ingestion.controller.vo.IngestionJobVO;
import com.lzj.rag.backend.ingestion.controller.vo.IngestionStageVO;
import com.lzj.rag.backend.ingestion.converter.IngestionWebConverter;
import com.lzj.rag.backend.ingestion.service.IngestionJobService;
import com.lzj.rag.backend.security.model.KnowledgeBasePrincipal;
import com.lzj.rag.framework.convention.page.PageResponse;
import com.lzj.rag.framework.convention.result.Result;
import com.lzj.rag.framework.starter.web.result.Results;

import jakarta.validation.constraints.*;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** 文档范围内的入库任务和阶段记录查询入口。 */
@RestController
@RequestMapping("/api/documents/{documentId}/ingestion-jobs")
public class IngestionQueryController {

    /** 负责持久化入库任务及租约状态迁移的业务服务。 */
    private final IngestionJobService jobs;

    /**
     * 初始化当前组件，保存业务依赖及本次使用的配置。
     *
     * @param jobs 负责持久化入库任务及租约状态迁移的业务服务。
     */
    public IngestionQueryController(IngestionJobService jobs) {
        this.jobs = jobs;
    }

    /**
     * 分页查询，将当前认证身份和接口参数交给对应业务服务处理。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param documentId 文档唯一标识。
     * @param current 当前分页页码，从一开始。
     * @param size 分页容量或当前数据的字节数。
     * @param status 当前业务操作或任务状态。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @GetMapping
    public Result<PageResponse<IngestionJobVO>> list(
            @AuthenticationPrincipal KnowledgeBasePrincipal actor,
            @PathVariable UUID documentId,
            @RequestParam(defaultValue = "1") @Min(1) @Max(1000000) int current,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "") @Size(max = 32) String status) {
        // 集合按原顺序转换为接口响应。
        return Results.success(
                jobs.list(actor, documentId, current, size, status)
                        .convert(IngestionWebConverter::toIngestionJobVO));
    }

    /**
     * 查询详情，将当前认证身份和接口参数交给对应业务服务处理。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param documentId 文档唯一标识。
     * @param jobId 持久化入库任务标识。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @GetMapping("/{jobId}")
    public Result<IngestionJobVO> get(
            @AuthenticationPrincipal KnowledgeBasePrincipal actor,
            @PathVariable UUID documentId,
            @PathVariable UUID jobId) {
        return Results.success(
                IngestionWebConverter.toIngestionJobVO(jobs.getOwnedJob(actor, documentId, jobId)));
    }

    /**
     * 分页查询任务阶段，将当前认证身份和接口参数交给对应业务服务处理。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param documentId 文档唯一标识。
     * @param jobId 持久化入库任务标识。
     * @param current 当前分页页码，从一开始。
     * @param size 分页容量或当前数据的字节数。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @GetMapping("/{jobId}/stages")
    public Result<PageResponse<IngestionStageVO>> stages(
            @AuthenticationPrincipal KnowledgeBasePrincipal actor,
            @PathVariable UUID documentId,
            @PathVariable UUID jobId,
            @RequestParam(defaultValue = "1") @Min(1) @Max(1000000) int current,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        // 集合按原顺序转换为接口响应。
        return Results.success(
                jobs.stages(actor, documentId, jobId, current, size)
                        .convert(IngestionWebConverter::toIngestionStageVO));
    }
}
