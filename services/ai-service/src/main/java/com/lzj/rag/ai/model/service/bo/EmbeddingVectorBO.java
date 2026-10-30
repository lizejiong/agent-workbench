package com.lzj.rag.ai.model.service.bo;

import java.util.List;

/**
 * 带原始输入索引的向量业务数据。
 *
 * @param index 结果在当前输入列表中的零起始位置，用于恢复原顺序。
 * @param values 当前向量各维度的有序数值。
 */
public record EmbeddingVectorBO(int index, List<Double> values) {}
