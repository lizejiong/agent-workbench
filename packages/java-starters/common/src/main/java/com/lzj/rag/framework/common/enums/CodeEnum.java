package com.lzj.rag.framework.common.enums;

/**
 * 使用整数码值存储或传输的枚举契约。
 */
public interface CodeEnum {

    /**
     * 获取枚举对应的稳定码值。
     *
     * @return 枚举码值
     */
    Integer code();
}
