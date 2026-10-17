package com.lzj.rag.framework.common.enums;

/**
 * 通用的是/否标识。
 */
public enum FlagEnum implements CodeEnum {

    /** 否。 */
    NO(0),
    /** 是。 */
    YES(1);

    private final Integer code;

    FlagEnum(Integer code) {
        this.code = code;
    }

    @Override
    public Integer code() {
        return code;
    }
}
