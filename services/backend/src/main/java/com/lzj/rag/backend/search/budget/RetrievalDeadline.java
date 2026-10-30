package com.lzj.rag.backend.search.budget;

import com.lzj.rag.backend.knowledgebase.exception.KnowledgeBaseException;

import java.time.*;

/** 使用单调时钟维护总预算，并为外部调用提供受限的绝对截止时间。 */
public final class RetrievalDeadline {

    /** 使用单调时钟记录的操作开始时刻。 */
    private final long started = System.nanoTime();

    /** 当前范围的结束位置或局部阶段截止点。 */
    private final long end;

    /** 当前阶段使用的绝对截止时间。 */
    private final Instant instant;

    /**
     * 初始化当前组件，保存业务依赖及本次使用的配置。
     *
     * @param duration 当前计算得到的时间间隔。
     */
    public RetrievalDeadline(Duration duration) {
        end = started + duration.toNanos();
        instant = Instant.now().plus(duration);
    }

    /**
     * 使用单调时钟计算本次检索剩余纳秒预算。
     *
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public long remainingNanos() {
        check();
        return Math.max(1, end - System.nanoTime());
    }

    /**
     * 使用单调时钟计算本次检索已耗费的毫秒数。
     *
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public long elapsedMillis() {
        return Duration.ofNanos(System.nanoTime() - started).toMillis();
    }

    /**
     * 将剩余总预算转换为当前时刻对应的绝对截止时间。
     *
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public Instant instant() {
        check();
        return instant;
    }

    /**
     * 取局部阶段预算与剩余总预算的较小值作为截止时间。
     *
     * @param duration 当前计算得到的时间间隔。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public Instant cap(Duration duration) {
        check();
        // local：当前元素本地名、局部解析器或阶段截止时间。
        Instant local = Instant.now().plus(duration);
        return local.isBefore(instant) ? local : instant;
    }

    /** 检查线程取消状态与剩余预算，过期时抛出检索超时异常。 */
    public void check() {
        if (Thread.currentThread().isInterrupted()) throw cancelled();
        if (System.nanoTime() >= end) throw timeout();
    }

    /**
     * 检查线程取消状态与剩余预算，过期时抛出检索超时异常。
     *
     * @param deadline 本阶段或本次请求必须完成的截止时间。
     */
    public static void check(Instant deadline) {
        if (Thread.currentThread().isInterrupted()) throw cancelled();
        if (!Instant.now().isBefore(deadline)) throw timeout();
    }

    /**
     * 创建调用预算耗尽的超时异常。
     *
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public static KnowledgeBaseException timeout() {
        return new KnowledgeBaseException(504, "KB_SEARCH_TIMEOUT", "Retrieval deadline exceeded");
    }

    /**
     * 创建检索被中断或取消的业务异常。
     *
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public static KnowledgeBaseException cancelled() {
        return new KnowledgeBaseException(503, "KB_SEARCH_CANCELLED", "Retrieval was cancelled");
    }
}
