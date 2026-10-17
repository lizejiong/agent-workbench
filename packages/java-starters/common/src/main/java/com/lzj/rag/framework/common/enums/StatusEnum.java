package com.lzj.rag.framework.common.enums;

/**
 * 通用启用状态。
 */
public enum StatusEnum implements CodeEnum {

    /** 禁用。 */
    DISABLED(0),
    /** 启用。 */
    ENABLED(1);

    private final Integer code;

    StatusEnum(Integer code) {
        this.code = code;
    }

    @Override
    public Integer code() {
        return code;
    }
}
