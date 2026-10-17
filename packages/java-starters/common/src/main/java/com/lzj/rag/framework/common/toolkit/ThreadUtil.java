package com.lzj.rag.framework.common.toolkit;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 线程工具。
 */
public final class ThreadUtil {

    private ThreadUtil() {
    }

    /**
     * 创建按前缀和递增序号命名的线程工厂。
     *
     * @param threadNamePrefix 线程名称前缀
     * @return 线程工厂
     */
    public static ThreadFactory newThreadFactory(String threadNamePrefix) {
        if (threadNamePrefix == null || threadNamePrefix.isBlank()) {
            throw new IllegalArgumentException("thread name prefix must not be blank");
        }
        AtomicInteger sequence = new AtomicInteger(1);
        return runnable -> new Thread(runnable, threadNamePrefix + "-" + sequence.getAndIncrement());
    }

    /**
     * 休眠指定时长；被中断时恢复中断标记，由上层决定后续处理。
     *
     * @param millis 休眠毫秒数，不能为负数
     */
    public static void sleep(long millis) {
        if (millis < 0) {
            throw new IllegalArgumentException("millis must not be negative");
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            // Thread.sleep 会清除中断标记，必须恢复，避免上层任务丢失取消信号。
            Thread.currentThread().interrupt();
        }
    }
}
