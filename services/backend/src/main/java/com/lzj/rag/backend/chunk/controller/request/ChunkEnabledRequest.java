package com.lzj.rag.backend.chunk.controller.request;

import jakarta.validation.constraints.*;

import java.util.*;

/**
 * 文档或切片启停请求，携带乐观锁版本。
 *
 * @param version 当前记录版本或本次请求固定的版本标记。
 * @param enabled 是否启用该资源或能力。
 */
public record ChunkEnabledRequest(@NotNull @Positive Long version, @NotNull Boolean enabled) {}
