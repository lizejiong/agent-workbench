package com.lzj.rag.backend.search;

import com.lzj.rag.backend.knowledgebase.KnowledgeBaseException;
import java.time.*;

/** One monotonic budget for authorization, recall, model calls and evidence assembly. */
final class RetrievalDeadline {
    private final long started = System.nanoTime();
    private final long end;
    private final Instant instant;

    RetrievalDeadline(Duration duration) {
        end = started + duration.toNanos();
        instant = Instant.now().plus(duration);
    }

    long remainingNanos() { check(); return Math.max(1, end - System.nanoTime()); }
    long elapsedMillis() { return Duration.ofNanos(System.nanoTime() - started).toMillis(); }
    Instant instant() { check(); return instant; }
    Instant cap(Duration duration) {
        check();
        Instant local = Instant.now().plus(duration);
        return local.isBefore(instant) ? local : instant;
    }
    void check() {
        if (Thread.currentThread().isInterrupted()) throw cancelled();
        if (System.nanoTime() >= end) throw timeout();
    }
    static void check(Instant deadline) {
        if (Thread.currentThread().isInterrupted()) throw cancelled();
        if (!Instant.now().isBefore(deadline)) throw timeout();
    }
    static KnowledgeBaseException timeout() {
        return new KnowledgeBaseException(504, "KB_SEARCH_TIMEOUT", "Retrieval deadline exceeded");
    }
    static KnowledgeBaseException cancelled() {
        return new KnowledgeBaseException(503, "KB_SEARCH_CANCELLED", "Retrieval was cancelled");
    }
}
