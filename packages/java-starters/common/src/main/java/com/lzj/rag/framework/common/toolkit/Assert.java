package com.lzj.rag.framework.common.toolkit;

import com.lzj.rag.framework.convention.exception.ClientException;

/**
 * 参数断言工具。
 * <p>
 * 断言失败时统一抛出 {@link ClientException}，便于服务层将参数错误转换为标准响应。
 */
public final class Assert {

    private Assert() {
    }

    /**
     * 断言表达式为真。
     *
     * @param expression 待校验表达式
     * @param message 断言失败时返回的提示信息
     */
    public static void isTrue(boolean expression, String message) {
        if (!expression) {
            throw new ClientException(message);
        }
    }

    /**
     * 断言对象非空，并返回原对象以支持连续调用。
     *
     * @param value 待校验对象
     * @param message 断言失败时返回的提示信息
     * @param <T> 对象类型
     * @return 原对象
     */
    public static <T> T notNull(T value, String message) {
        isTrue(value != null, message);
        return value;
    }

    /**
     * 断言字符串非空且至少包含一个非空白字符。
     *
     * @param value 待校验字符串
     * @param message 断言失败时返回的提示信息
     * @return 原字符串
     */
    public static String notBlank(String value, String message) {
        isTrue(value != null && !value.isBlank(), message);
        return value;
    }
}
