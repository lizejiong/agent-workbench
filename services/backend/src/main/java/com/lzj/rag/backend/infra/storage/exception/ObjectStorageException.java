package com.lzj.rag.backend.infra.storage.exception;

/** 存储能力的技术失败分类，由业务适配层决定对应的业务状态。 */
public class ObjectStorageException extends RuntimeException {

    /** 可稳定识别的存储失败分类。 */
    private final String code;

    /**
     * 保存存储失败分类及不含连接凭据的说明。
     *
     * @param code 存储失败的稳定分类。
     * @param message 不包含源内容与认证信息的失败说明。
     */
    public ObjectStorageException(String code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * @return 当前存储失败的稳定分类。
     */
    public String code() {
        return code;
    }
}
