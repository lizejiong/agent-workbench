package com.lzj.rag.backend.chunk.controller.request;

import jakarta.validation.constraints.*;

import java.util.*;

/**
 * 按指定切片或整个文档执行启停的请求。
 *
 * @param version 当前记录版本或本次请求固定的版本标记。
 * @param enabled 是否启用该资源或能力。
 * @param chunkIds 本次维护或审计涉及的切片标识集合。
 * @param applyAll 是否对当前文档全部切片执行维护。
 */
public record ChunkBatchRequest(
        @NotNull @Positive Long version,
        @NotNull Boolean enabled,
        List<@NotNull UUID> chunkIds,
        boolean applyAll) {}
