package com.lzj.rag.backend.parser.strategy;

import com.lzj.rag.backend.parser.service.bo.ParsedDocumentBO;
import com.lzj.rag.backend.parser.service.bo.ParserInputBO;

import java.util.Set;

/** 文档解析策略契约，声明支持的格式、解析器标识和处理档位。 */
public interface DocumentParser {

    /**
     * 返回解析器的稳定策略标识。
     *
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    String profile();

    /**
     * 返回当前解析策略支持的 MIME 类型集合。
     *
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    Set<String> mimeTypes();

    /**
     * 将文本或受限文件输入转换为带来源信息的结构化文档。
     *
     * @param utf8Text 已经通过严格 UTF-8 解码的正文。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    ParsedDocumentBO parse(String utf8Text);

    /**
     * 返回当前解析器支持的处理档位。
     *
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    default String parseProfile() {
        return "fast";
    }

    /**
     * 将文本或受限文件输入转换为带来源信息的结构化文档。
     *
     * @param input 解析源文件及其字符、时间约束。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    default ParsedDocumentBO parse(ParserInputBO input) {
        return parse(input.text());
    }
}
