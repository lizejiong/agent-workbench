package com.lzj.rag.backend.search.controller;

import com.lzj.rag.backend.search.controller.vo.KeywordSyncVO;
import com.lzj.rag.backend.search.converter.SearchWebConverter;
import com.lzj.rag.backend.search.service.KeywordSyncService;
import com.lzj.rag.backend.security.model.KnowledgeBasePrincipal;
import com.lzj.rag.framework.convention.page.PageResponse;
import com.lzj.rag.framework.convention.result.Result;
import com.lzj.rag.framework.starter.web.result.Results;

import jakarta.validation.constraints.*;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** 关键词索引同步任务的查询与重试入口。 */
@RestController
public class KeywordSyncController {

    /** 负责关键词同步任务及索引版本发布的业务服务。 */
    private final KeywordSyncService sync;

    /**
     * 初始化当前组件，保存业务依赖及本次使用的配置。
     *
     * @param sync 负责关键词同步任务及索引版本发布的业务服务。
     */
    public KeywordSyncController(KeywordSyncService sync) {
        this.sync = sync;
    }

    /**
     * 查询详情，将当前认证身份和接口参数交给对应业务服务处理。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param id 当前业务对象的唯一标识。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @GetMapping("/api/documents/{id}/keyword-index")
    public Result<KeywordSyncVO> get(
            @AuthenticationPrincipal KnowledgeBasePrincipal actor, @PathVariable UUID id) {
        return Results.success(SearchWebConverter.toKeywordSyncVO(sync.get(actor, id)));
    }

    /**
     * 分页查询，将当前认证身份和接口参数交给对应业务服务处理。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param kbId 知识库唯一标识。
     * @param current 当前分页页码，从一开始。
     * @param size 分页容量或当前数据的字节数。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @GetMapping("/api/kbs/{kbId}/keyword-sync")
    public Result<PageResponse<KeywordSyncVO>> list(
            @AuthenticationPrincipal KnowledgeBasePrincipal actor,
            @PathVariable UUID kbId,
            @RequestParam(defaultValue = "1") @Min(1) @Max(1000000) int current,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        // 集合按原顺序转换为接口响应。
        return Results.success(
                sync.list(actor, kbId, current, size).convert(SearchWebConverter::toKeywordSyncVO));
    }

    /**
     * 重试失败操作，将当前认证身份和接口参数交给对应业务服务处理。
     *
     * @param actor 本次业务操作的租户及用户身份。
     * @param kbId 知识库唯一标识。
     * @param id 当前业务对象的唯一标识。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @PostMapping("/api/kbs/{kbId}/keyword-sync/{id}/retry")
    public Result<KeywordSyncVO> retry(
            @AuthenticationPrincipal KnowledgeBasePrincipal actor,
            @PathVariable UUID kbId,
            @PathVariable UUID id) {
        return Results.success(SearchWebConverter.toKeywordSyncVO(sync.retry(actor, kbId, id)));
    }
}
