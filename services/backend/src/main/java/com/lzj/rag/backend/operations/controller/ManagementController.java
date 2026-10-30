package com.lzj.rag.backend.operations.controller;

import com.fasterxml.jackson.databind.*;
import com.lzj.rag.backend.operations.controller.vo.ContentAuditVO;
import com.lzj.rag.backend.operations.converter.OperationsWebConverter;
import com.lzj.rag.backend.operations.service.ManagementService;
import com.lzj.rag.backend.security.model.KnowledgeBasePrincipal;
import com.lzj.rag.framework.convention.page.PageResponse;
import com.lzj.rag.framework.convention.result.Result;
import com.lzj.rag.framework.starter.web.result.Results;

import jakarta.validation.constraints.*;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** 提供管理导航及权限范围内的维护审计查询。 */
@RestController
public class ManagementController {

    /** 管理导航及审计查询业务服务。 */
    private final ManagementService service;

    /**
     * 注入管理查询业务服务。
     *
     * @param service 负责业务授权和只读事务的查询入口。
     */
    public ManagementController(ManagementService service) {
        this.service = service;
    }

    /**
     * 返回管理入口及服务能力导航。
     *
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @GetMapping("/api/knowledge/management")
    public Result<Map<String, Object>> entry() {
        return Results.success(service.entry());
    }

    /**
     * 在知识库归属范围内分页读取维护审计记录。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param kbId 知识库唯一标识。
     * @param current 当前分页页码，从一开始。
     * @param size 分页容量或当前数据的字节数。
     * @param action 本次执行的业务操作名称或受限执行函数。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @GetMapping("/api/kbs/{kbId}/audit")
    public Result<PageResponse<ContentAuditVO>> audit(
            @AuthenticationPrincipal KnowledgeBasePrincipal actor,
            @PathVariable UUID kbId,
            @RequestParam(defaultValue = "1") @Min(1) @Max(1000000) int current,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "") @Pattern(regexp = "[A-Z_]{0,64}") String action) {
        return Results.success(
                service.audit(actor, kbId, current, size, action)
                        .convert(OperationsWebConverter::toVO));
    }
}
