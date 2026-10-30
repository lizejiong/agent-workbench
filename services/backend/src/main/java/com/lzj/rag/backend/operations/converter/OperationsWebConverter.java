package com.lzj.rag.backend.operations.converter;

import com.lzj.rag.backend.operations.controller.vo.ContentAuditVO;
import com.lzj.rag.backend.operations.service.bo.ContentAuditBO;

/** 管理审计业务结果到 HTTP 响应的纯字段转换。 */
public final class OperationsWebConverter {

    /** 禁止实例化无状态转换工具。 */
    private OperationsWebConverter() {}

    /**
     * 转换一条内容维护审计记录。
     *
     * @param value 当前用户权限范围内的审计业务结果。
     * @return 保留操作人、切片和前后摘要的审计响应。
     */
    public static ContentAuditVO toVO(ContentAuditBO value) {
        return new ContentAuditVO(
                value.id(),
                value.documentId(),
                value.actorId(),
                value.action(),
                value.chunkIds(),
                value.beforeSummary(),
                value.afterSummary(),
                value.createdAt());
    }
}
