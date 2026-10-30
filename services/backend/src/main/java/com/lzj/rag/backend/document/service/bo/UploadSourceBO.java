package com.lzj.rag.backend.document.service.bo;

import java.io.IOException;
import java.io.InputStream;

/**
 * 与 HTTP 无关的源文件输入，允许上传、远程下载或其他入口复用同一套文件校验。
 *
 * @param originalFilename 调用方提供的原始文件名，进入流水线后还需校验。
 * @param size 源文件声明的字节数，实际读取时仍会独立检查容量。
 * @param source 可重新打开输入流的工厂，由消费方关闭每次取得的流。
 */
public record UploadSourceBO(String originalFilename, long size, StreamSource source) {

    /** 可抛出读取异常的源文件输入流工厂。 */
    @FunctionalInterface
    public interface StreamSource {

        /**
         * 打开源文件输入流。
         *
         * @return 由消费方负责关闭的输入流。
         * @throws IOException 源文件无法读取时抛出。
         */
        InputStream open() throws IOException;
    }

    /**
     * @return 调用方声明的原始文件名。
     */
    public String getOriginalFilename() {
        return originalFilename;
    }

    /**
     * @return 调用方声明的源文件字节数。
     */
    public long getSize() {
        return size;
    }

    /**
     * @return 声明的文件长度为零时返回 true。
     */
    public boolean isEmpty() {
        return size == 0;
    }

    /**
     * 打开本次校验需要消费的源文件流。
     *
     * @return 由校验流水线负责关闭的输入流。
     * @throws IOException 源文件无法读取时抛出。
     */
    public InputStream getInputStream() throws IOException {
        return source.open();
    }
}
