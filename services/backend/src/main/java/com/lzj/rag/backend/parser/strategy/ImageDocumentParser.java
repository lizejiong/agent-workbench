package com.lzj.rag.backend.parser.strategy;

import com.lzj.rag.backend.knowledgebase.exception.KnowledgeBaseException;
import com.lzj.rag.backend.model.service.VlmClient;
import com.lzj.rag.backend.parser.config.FormatProperties;
import com.lzj.rag.backend.parser.service.bo.ParsedBlockBO;
import com.lzj.rag.backend.parser.service.bo.ParsedDocumentBO;
import com.lzj.rag.backend.parser.service.bo.ParserInputBO;
import com.lzj.rag.backend.parser.support.SupportedFiles;

import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.util.*;

import javax.imageio.ImageIO;

/** 校验图片尺寸后调用视觉模型，将描述组织为结构化文档块。 */
@Component
public class ImageDocumentParser implements DocumentParser {

    /** 为图片生成结构化描述的视觉模型客户端。 */
    private final VlmClient vlm;

    /** 本地解析、MinerU 和视觉模型的开关、容量与时间限制。 */
    private final FormatProperties config;

    /**
     * 初始化当前组件，保存业务依赖及本次使用的配置。
     *
     * @param vlm 为图片生成结构化描述的视觉模型客户端。
     * @param config 本地解析、MinerU 和视觉模型的开关、容量与时间限制。
     */
    public ImageDocumentParser(VlmClient vlm, FormatProperties config) {
        this.vlm = vlm;
        this.config = config;
    }

    /**
     * 返回解析器的稳定策略标识。
     *
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public String profile() {
        return "BINARY";
    }

    /**
     * 返回当前解析策略支持的 MIME 类型集合。
     *
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public Set<String> mimeTypes() {
        return SupportedFiles.IMAGES;
    }

    /**
     * 拒绝未经文件校验的纯文本输入；该解析策略要求使用受限文件输入。
     *
     * @param text 当前待处理或生成的文本内容。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public ParsedDocumentBO parse(String text) {
        throw new KnowledgeBaseException(
                415, "KB_PARSER_UNAVAILABLE", "Image parser requires verified file input");
    }

    /**
     * 按当前解析策略读取输入，在容量与时间限制内生成统一结构块。
     *
     * @param input 解析源文件及其字符、时间约束。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @Override
    public ParsedDocumentBO parse(ParserInputBO input) {
        try {
            if (Files.size(input.path()) > config.maxImageBytes()) throw limit();
            // width：图片宽度，单位为像素。
            int width;
            // height：图片高度，单位为像素。
            int height;
            // stream：当前需要关闭的文件或解析资源流。
            try (var stream = ImageIO.createImageInputStream(input.path().toFile())) {
                // readers：支持当前图片格式的解码器集合。
                var readers = ImageIO.getImageReaders(stream);
                if (!readers.hasNext()) throw new java.io.IOException("Invalid image");
                // reader：当前受限文本或图片读取器。
                var reader = readers.next();
                try {
                    reader.setInput(stream, true, true);
                    width = reader.getWidth(0);
                    height = reader.getHeight(0);
                } finally {
                    reader.dispose();
                }
            }
            if (width <= 0
                    || height <= 0
                    || width > 10000
                    || height > 10000
                    || (long) width * height > config.maxImagePixels()) throw limit();
            // description：资源描述或视觉模型生成的图片描述。
            var description =
                    vlm.describe(
                            Files.readAllBytes(input.path()),
                            input.mime(),
                            input.requestId(),
                            input.deadline());
            // text：当前待处理或生成的文本内容。
            String text = description.text();
            if (text.length() > input.maxParsedChars()) throw limit();
            // paragraphs：当前章节内等待组合的段落集合。
            var paragraphs = new TextDocumentParser().parse(text);
            // blocks：按源文档顺序排列的结构块。
            var blocks = new ArrayList<ParsedBlockBO>();
            // block：当前处理的结构块。
            for (var block : paragraphs.blocks())
                blocks.add(
                        new ParsedBlockBO(
                                block.index(),
                                block.blockType(),
                                block.content(),
                                List.of(),
                                Map.of(
                                        "imageNumber",
                                        1,
                                        "imageWidth",
                                        width,
                                        "imageHeight",
                                        height,
                                        "generated",
                                        true,
                                        "provenance",
                                        "VLM_DESCRIPTION_AND_OCR",
                                        "vlmModel",
                                        description.model()),
                                List.of(),
                                List.of()));
            if (blocks.isEmpty())
                throw new KnowledgeBaseException(
                        502, "KB_VLM_INVALID_RESPONSE", "Image description is empty");
            return new ParsedDocumentBO(text, List.copyOf(blocks), "ai-vision-description-ocr-v1");
        } catch (KnowledgeBaseException error) {
            // error：本次处理捕获的异常，用于归类失败原因。
            throw error;
        } catch (java.io.IOException error) {
            // error：本次处理捕获的异常，用于归类失败原因。
            throw new KnowledgeBaseException(
                    422, "KB_IMAGE_INVALID", "Image dimensions or bytes cannot be read");
        }
    }

    /**
     * 创建解析内容超过配置容量的异常。
     *
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private KnowledgeBaseException limit() {
        return new KnowledgeBaseException(
                422, "KB_IMAGE_LIMIT", "Image or description exceeds byte/pixel/text limits");
    }
}
