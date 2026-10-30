package com.lzj.rag.backend.model.service;

import com.lzj.rag.backend.model.service.bo.ImageDescriptionBO;

import java.time.Instant;

/** 图片描述能力契约，调用受上层请求截止时间约束。 */
public interface VlmClient {

    /**
     * 在给定截止时间内描述图片，并返回可用于后续解析的文本。
     *
     * @param image 需要视觉模型描述的原始图片字节。
     * @param mime 识别或选择的文件 MIME 类型。
     * @param requestId 请求跟踪标识或业务幂等键，按当前接口约定使用。
     * @param deadline 本阶段或本次请求必须完成的截止时间。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    ImageDescriptionBO describe(byte[] image, String mime, String requestId, Instant deadline);
}
