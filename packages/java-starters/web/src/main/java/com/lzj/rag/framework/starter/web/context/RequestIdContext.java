package com.lzj.rag.framework.starter.web.context;

/** 当前请求线程的关联 ID；跨线程和跨服务传播由调用方显式处理。 */
public final class RequestIdContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private RequestIdContext() {
    }

    public static String get() {
        return CURRENT.get();
    }

    public static void set(String requestId) {
        CURRENT.set(requestId);
    }

    public static void clear() {
        CURRENT.remove();
    }
}
