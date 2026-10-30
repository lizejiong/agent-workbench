package com.lzj.rag.ai.model.service.bo;

import java.util.List;

/**
 * 向量业务输入，包含文本、维度与可选的配置修订约束。
 *
 * @param text 待处理或返回的文本内容。
 * @param texts 本次操作的有序文本输入集合。
 * @param model 本次实际使用或请求固定的逻辑模型别名。
 * @param dimensions 本次向量结果使用的维度。
 * @param expectedConfigRevision 调用方固定的配置修订摘要；不一致时在请求供应商前拒绝调用。
 */
public record EmbedBO(
        String text,
        List<String> texts,
        String model,
        Integer dimensions,
        String expectedConfigRevision) {}
