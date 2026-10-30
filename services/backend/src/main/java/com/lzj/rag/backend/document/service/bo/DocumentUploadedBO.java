package com.lzj.rag.backend.document.service.bo;

/**
 * 上传结果，同时返回持久化操作记录和文档信息。
 *
 * @param document 当前处理的文档记录或文档标识。
 * @param replayed 是否复用了相同幂等键的已有结果。
 */
public record DocumentUploadedBO(DocumentBO document, boolean replayed) {}
