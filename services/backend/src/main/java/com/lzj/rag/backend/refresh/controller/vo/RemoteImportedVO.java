package com.lzj.rag.backend.refresh.controller.vo;

import com.lzj.rag.backend.document.controller.vo.DocumentVO;

import java.util.UUID;

/**
 * 导入结果及幂等重放标记。
 *
 * @param document 当前处理的文档记录或文档标识。
 * @param jobId 持久化入库任务标识。
 * @param replayed 是否复用了相同幂等键的已有结果。
 */
public record RemoteImportedVO(DocumentVO document, UUID jobId, boolean replayed) {}
