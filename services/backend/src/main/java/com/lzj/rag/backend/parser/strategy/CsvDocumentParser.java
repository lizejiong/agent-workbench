package com.lzj.rag.backend.parser.strategy;

import com.lzj.rag.backend.knowledgebase.exception.KnowledgeBaseException;
import com.lzj.rag.backend.parser.service.bo.ParsedDocumentBO;
import com.lzj.rag.backend.parser.service.bo.ParserInputBO;

import org.springframework.stereotype.Component;

import java.util.Set;

/** CSV 格式解析入口，委托受限的本地格式解析器保留表格结构。 */
@Component
public class CsvDocumentParser implements DocumentParser {

    /** 在受限子进程中解析本地文件的组件。 */
    private final LocalFormatParser local;

    /**
     * 初始化当前组件，保存业务依赖及本次使用的配置。
     *
     * @param local 在受限子进程中解析本地文件的组件。
     */
    public CsvDocumentParser(LocalFormatParser local) {
        this.local = local;
    }

    /**
     * 返回解析器的稳定策略标识。
     *
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public String profile() {
        return "CSV";
    }

    /**
     * 返回当前解析策略支持的 MIME 类型集合。
     *
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public Set<String> mimeTypes() {
        return Set.of("text/plain", "text/csv", "application/csv");
    }

    /**
     * 拒绝未经文件校验的纯文本输入；该解析策略要求使用受限文件输入。
     *
     * @param text 当前待处理或生成的文本内容。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public ParsedDocumentBO parse(String text) {
        throw new KnowledgeBaseException(
                415, "KB_PARSER_UNAVAILABLE", "CSV parser requires verified file input");
    }

    /**
     * 按当前解析策略读取输入，在容量与时间限制内生成统一结构块。
     *
     * @param input 解析源文件及其字符、时间约束。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @Override
    public ParsedDocumentBO parse(ParserInputBO input) {
        return local.parse(input);
    }
}
