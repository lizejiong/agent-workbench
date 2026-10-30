package com.lzj.rag.backend.model.service.bo;

import java.util.List;

/**
 * 整组嵌入向量和实际使用的 AI 配置修订，顺序对应输入文本。
 *
 * @param vectors 当前处理的有序向量集合。
 * @param aiConfigRevision 本次实际使用或任务固定的 AI 配置修订摘要。
 */
public record EmbeddingResultBO(List<float[]> vectors, String aiConfigRevision) {}
