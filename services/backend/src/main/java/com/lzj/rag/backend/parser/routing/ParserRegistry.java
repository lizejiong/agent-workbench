package com.lzj.rag.backend.parser.routing;

import com.lzj.rag.backend.knowledgebase.exception.KnowledgeBaseException;
import com.lzj.rag.backend.parser.strategy.DocumentParser;
import com.lzj.rag.backend.parser.support.SupportedFiles;

import org.springframework.stereotype.Component;

import java.util.*;

/** 按 MIME、解析器标识和档位选择唯一解析策略。 */
@Component
public class ParserRegistry {

    /** 按标识注册的文档解析策略集合。 */
    private final Map<String, DocumentParser> parsers = new HashMap<>();

    /**
     * 初始化当前组件，保存业务依赖及本次使用的配置。
     *
     * @param candidates 本次待领取的任务、可选解析策略或召回候选集合。
     */
    public ParserRegistry(List<DocumentParser> candidates) {
        // parser：当前选择的解析策略或解析器标识。
        for (var parser : candidates) /*
 * mime：识别或选择的文件 MIME 类型。
 */
            for (String mime : parser.mimeTypes()) {
                if (parsers.putIfAbsent(
                                parser.parseProfile() + ":" + parser.profile() + ":" + mime, parser)
                        != null)
                    throw new IllegalStateException(
                            "Duplicate parser claim for profile/MIME: "
                                    + parser.profile()
                                    + "/"
                                    + mime);
            }
    }

    /**
     * 按格式、解析器选择和处理档位解析唯一可用策略，不兼容时明确报错。
     *
     * @param mime 识别或选择的文件 MIME 类型。
     * @param requested 当前接口或请求声明的策略选择。
     * @param originalFilename 源文件原始名称。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public DocumentParser resolve(String mime, String requested, String originalFilename) {
        return resolve(mime, requested, originalFilename, "fast");
    }

    /**
     * 按格式、解析器选择和处理档位解析唯一可用策略，不兼容时明确报错。
     *
     * @param mime 识别或选择的文件 MIME 类型。
     * @param requested 当前接口或请求声明的策略选择。
     * @param originalFilename 源文件原始名称。
     * @param parseProfile 本次解析使用的快速或高保真档位。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public DocumentParser resolve(
            String mime, String requested, String originalFilename, String parseProfile) {
        // ext：规范化后的文件扩展名。
        String ext = SupportedFiles.extension(originalFilename);
        // profile：公开配置或解析策略标识。
        String profile =
                requested.equals("AUTO")
                        ? (ext.equals("txt")
                                ? "TEXT"
                                : Set.of("md", "markdown").contains(ext)
                                        ? "MARKDOWN"
                                        : ext.equals("csv") ? "CSV" : "BINARY")
                        : requested;
        // parser：当前选择的解析策略或解析器标识。
        var parser = parsers.get(parseProfile + ":" + profile + ":" + mime);
        // 只解析已声明别名；已经选定解析器后的失败不会触发静默降档。
        if (parser == null
                && parseProfile.equals("fidelity")
                && (Set.of("TEXT", "MARKDOWN", "CSV").contains(profile)
                        || SupportedFiles.EXCEL.contains(mime)
                        || SupportedFiles.IMAGES.contains(mime)))
            parser = parsers.get("fast:" + profile + ":" + mime);
        if (parser == null)
            throw new KnowledgeBaseException(
                    415, "KB_PARSER_UNAVAILABLE", "No parser registered for this MIME and profile");
        return parser;
    }
}
