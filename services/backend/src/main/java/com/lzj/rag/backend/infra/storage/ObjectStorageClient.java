package com.lzj.rag.backend.infra.storage;

import java.io.InputStream;
import java.nio.file.Path;

/** 源文件对象存储契约，支持流式读取和幂等删除。 */
public interface ObjectStorageClient {

    /**
     * 将已校验临时文件写入指定对象键，并保留内容类型和摘要信息。
     *
     * @param objectKey 对象存储中的源文件键。
     * @param file 待校验、读取或写入的源文件。
     * @param mimeType 源文件 MIME 类型。
     * @param sha256 文件 SHA-256 摘要的十六进制表示。
     */
    void put(String objectKey, Path file, String mimeType, String sha256);

    /**
     * 打开源对象的输入流，由调用方负责关闭。
     *
     * @param objectKey 对象存储中的源文件键。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    InputStream open(String objectKey);

    /**
     * 幂等删除指定源对象，已不存在时视为完成。
     *
     * @param objectKey 对象存储中的源文件键。
     */
    void delete(String objectKey);
}
