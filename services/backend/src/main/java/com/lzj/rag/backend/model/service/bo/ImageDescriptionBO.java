package com.lzj.rag.backend.model.service.bo;

/**
 * 图片描述及实际使用的模型身份信息。
 *
 * @param text 当前待处理或生成的文本内容。
 * @param model 当前固定的模型配置或 AI 返回的模型身份节点。
 */
public record ImageDescriptionBO(String text, String model) {}
