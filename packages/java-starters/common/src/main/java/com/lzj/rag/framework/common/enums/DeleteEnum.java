package com.lzj.rag.framework.common.enums;

/**
 * 逻辑删除标记。
 */
public enum DeleteEnum implements CodeEnum {

    /** 未删除。 */
    NORMAL(0),
    /** 已删除。 */
    DELETED(1);

    private final Integer code;

    DeleteEnum(Integer code) {
        this.code = code;
    }

    @Override
    public Integer code() {
        return code;
    }
}
