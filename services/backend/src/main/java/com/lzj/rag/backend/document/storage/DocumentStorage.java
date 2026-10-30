package com.lzj.rag.backend.document.storage;

import com.lzj.rag.backend.infra.storage.ObjectStorageClient;
import com.lzj.rag.backend.infra.storage.exception.ObjectStorageException;
import com.lzj.rag.backend.knowledgebase.exception.KnowledgeBaseException;

import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Path;

/** 文档存储的业务适配层，将独立存储客户端的技术失败转换为既有业务错误。 */
@Component
public class DocumentStorage {

    /** 不依赖知识库业务类型的底层对象存储客户端。 */
    private final ObjectStorageClient client;

    /**
     * 注入当前配置或测试替换的对象存储客户端。
     *
     * @param client 负责实际流式存取的技术客户端。
     */
    public DocumentStorage(ObjectStorageClient client) {
        this.client = client;
    }

    /**
     * 写入已经校验的源文件。
     *
     * @param key 目标对象键。
     * @param file 已校验的本地临时文件。
     * @param mime 源文件真实 MIME 类型。
     * @param hash 源文件 SHA-256 摘要。
     */
    public void put(String key, Path file, String mime, String hash) {
        try {
            client.put(key, file, mime, hash);
        } catch (ObjectStorageException error) {
            // error：技术存储失败；保留原有业务错误码和 HTTP 状态。
            throw new KnowledgeBaseException(503, error.code(), error.getMessage());
        }
    }

    /**
     * 打开源文件流。
     *
     * @param key 已通过业务授权的对象键。
     * @return 由调用方负责关闭的输入流。
     */
    public InputStream open(String key) {
        try {
            return client.open(key);
        } catch (ObjectStorageException error) {
            // error：技术读取失败，不向业务层暴露供应商连接细节。
            throw new KnowledgeBaseException(503, error.code(), error.getMessage());
        }
    }

    /**
     * 幂等删除不再被业务引用的源对象。
     *
     * @param key 已登记到持久化清理流程的对象键。
     */
    public void delete(String key) {
        try {
            client.delete(key);
        } catch (ObjectStorageException error) {
            // error：技术删除失败，交由业务清理队列决定后续重试。
            throw new KnowledgeBaseException(503, error.code(), error.getMessage());
        }
    }
}
