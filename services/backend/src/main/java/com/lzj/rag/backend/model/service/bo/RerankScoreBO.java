package com.lzj.rag.backend.model.service.bo;

import java.util.*;

/**
 * 重排返回的原输入位置和有限相关性分数。
 *
 * @param index 当前元素位置或关键词索引名称。
 * @param score 当前候选的相关性或融合分数。
 */
public record RerankScoreBO(int index, double score) {}
