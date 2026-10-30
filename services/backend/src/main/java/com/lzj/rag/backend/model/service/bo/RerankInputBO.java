package com.lzj.rag.backend.model.service.bo;

import java.util.*;

/**
 * 带切片身份的重排文档输入。
 *
 * @param chunkId 切片唯一标识。
 * @param content 用于展示或维护的正文内容。
 */
public record RerankInputBO(UUID chunkId, String content) {}
