package com.lzj.rag.backend.model.client;

import com.lzj.rag.backend.knowledgebase.exception.KnowledgeBaseException;
import com.lzj.rag.backend.model.service.VlmClient;
import com.lzj.rag.backend.model.service.bo.ImageDescriptionBO;
import com.lzj.rag.backend.parser.config.FormatProperties;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

/** 调用视觉模型生成图片描述，将结果转换为可追溯的解析内容。 */
@Component
public class AiVlmClient implements VlmClient {

    /** 复用受限 HTTP 传输的 AI 嵌入客户端。 */
    private final AiEmbeddingClient transport;

    /** 本地解析、MinerU 和视觉模型的开关、容量与时间限制。 */
    private final FormatProperties config;

    /**
     * 初始化当前组件，保存业务依赖及本次使用的配置。
     *
     * @param transport 复用受限 HTTP 传输的 AI 嵌入客户端。
     * @param config 本地解析、MinerU 和视觉模型的开关、容量与时间限制。
     */
    public AiVlmClient(AiEmbeddingClient transport, FormatProperties config) {
        this.transport = transport;
        this.config = config;
    }

    /**
     * 将图片编码后请求视觉模型，校验描述内容并保留实际模型信息。
     *
     * @param image 需要视觉模型描述的原始图片字节。
     * @param mime 识别或选择的文件 MIME 类型。
     * @param requestId 请求跟踪标识或业务幂等键，按当前接口约定使用。
     * @param deadline 本阶段或本次请求必须完成的截止时间。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public ImageDescriptionBO describe(
            byte[] image, String mime, String requestId, Instant deadline) {
        if (!config.vlm().enabled())
            throw new KnowledgeBaseException(
                    503,
                    "KB_VLM_UNAVAILABLE",
                    "Image parsing requires configured kb.formats.vlm and the existing AI vision service");
        if (image.length == 0 || image.length > config.maxImageBytes())
            throw new KnowledgeBaseException(
                    422, "KB_IMAGE_LIMIT", "Image exceeds configured byte limit");
        // message：对外展示的业务错误或状态说明。
        var message =
                Map.of(
                        "role",
                        "user",
                        "content",
                        "请忠实描述图片中的可见内容，并逐行转写可辨识的文字。区分描述和文字转写；无法辨识时明确说明，不推断缺失内容。",
                        "images",
                        List.of(
                                Map.of(
                                        "base64",
                                        Base64.getEncoder().encodeToString(image),
                                        "mediaType",
                                        mime)));
        // body：需要发送或当前接收的 HTTP 正文。
        var body =
                Map.<String, Object>of(
                        "messages",
                        List.of(message),
                        "task",
                        "vision",
                        "preferredModel",
                        config.vlm().model(),
                        "thinking",
                        false,
                        "maxOutputTokens",
                        config.vlm().maxOutputTokens());
        // result：当前步骤产生的处理结果。
        var result = transport.visionCall(body, requestId, deadline);
        // text：当前待处理或生成的文本内容。
        String text = result.path("content").asText("").strip();
        if (!config.vlm().model().equals(result.path("model").asText()))
            throw new KnowledgeBaseException(
                    502,
                    "KB_VLM_MODEL_CHANGED",
                    "Vision response used a different model; no fallback result is accepted");
        // c：当前处理的 Unicode 码点或 SAX 字符片段。
        if (text.isBlank()
                || text.codePoints().anyMatch(c -> c == 0 || (c >= 0xd800 && c <= 0xdfff))
                || "length".equals(result.path("finishReason").asText()))
            throw new KnowledgeBaseException(
                    502,
                    "KB_VLM_INVALID_RESPONSE",
                    "Vision description is empty, invalid or truncated");
        return new ImageDescriptionBO(text, config.vlm().model());
    }
}
