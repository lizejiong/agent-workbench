package com.lzj.rag.backend.document.service.bo;

import java.io.InputStream;

/**
 * 授权后的源文件内容及下载元数据，不包含持久化实体或对象存储键。
 *
 * @param content 由调用方负责关闭的文件流；HTTP 入口交由 Spring 响应资源关闭。
 */
public record DocumentFileBO(
        String originalFilename, String mimeType, long fileSize, InputStream content) {}
