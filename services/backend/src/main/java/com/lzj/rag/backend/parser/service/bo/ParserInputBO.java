package com.lzj.rag.backend.parser.service.bo;

import com.lzj.rag.backend.knowledgebase.exception.KnowledgeBaseException;
import com.lzj.rag.backend.parser.support.SupportedFiles;

import java.nio.file.Path;
import java.time.Instant;

/**
 * 解析输入文件和请求约束，包含字符上限及本次执行截止时间。
 *
 * @param path 当前需要读取、写入或清理的文件路径。
 * @param filename 经过业务校验的文件名。
 * @param mime 识别或选择的文件 MIME 类型。
 * @param maxParsedChars 单个文档解析后允许保留的字符数上限。
 * @param requestId 请求跟踪标识或业务幂等键，按当前接口约定使用。
 * @param deadline 本阶段或本次请求必须完成的截止时间。
 */
public record ParserInputBO(
        Path path,
        String filename,
        String mime,
        int maxParsedChars,
        String requestId,
        Instant deadline) {

    /**
     * 按严格 UTF-8 和字符预算读取文本源文件。
     *
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public String text() {
        try {
            return SupportedFiles.text(path, maxParsedChars);
        } catch (java.io.IOException e) {
            // e：本次捕获或匹配的异常，用于保留业务分类及失败原因。
            throw new KnowledgeBaseException(
                    422, "KB_SOURCE_INVALID", "Source is not valid bounded UTF-8 text");
        }
    }
}
