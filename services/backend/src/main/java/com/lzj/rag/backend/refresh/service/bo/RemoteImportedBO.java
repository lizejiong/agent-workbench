package com.lzj.rag.backend.refresh.service.bo;

import com.lzj.rag.backend.document.service.bo.DocumentBO;

import java.util.UUID;

/**
 * 导入结果及幂等重放标记。
 *
 * @param document 当前处理的文档记录或文档标识。
 * @param jobId 持久化入库任务标识。
 * @param replayed 是否复用了相同幂等键的已有结果。
 */
public record RemoteImportedBO(DocumentBO document, UUID jobId, boolean replayed) {}
