package com.lzj.rag.framework.designpattern.chain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Immutable, ordered chain of handlers with explicit stop semantics.
 *
 * @param <T> request type handled by the chain
 */
public final class ResponsibilityChain<T> {

    private final List<ChainHandler<? super T>> handlers;

    private ResponsibilityChain(List<ChainHandler<? super T>> handlers) {
        this.handlers = handlers;
    }

    public static <T> ChainBuilder<T> builder() {
        return new ChainBuilder<>();
    }

    public ChainDecision execute(T request) {
        Objects.requireNonNull(request, "request must not be null");
        for (ChainHandler<? super T> handler : handlers) {
            ChainDecision decision = Objects.requireNonNull(
                    handler.handle(request),
                    "handler decision must not be null"
            );
            if (decision == ChainDecision.STOP) {
                return ChainDecision.STOP;
            }
        }
        return ChainDecision.CONTINUE;
    }

    public int size() {
        return handlers.size();
    }

    public static final class ChainBuilder<T> {

        private final List<ChainHandler<? super T>> handlers = new ArrayList<>();

        private ChainBuilder() {
        }

        public ChainBuilder<T> add(ChainHandler<? super T> handler) {
            handlers.add(Objects.requireNonNull(handler, "handler must not be null"));
            return this;
        }

        public ChainBuilder<T> addAll(
                Collection<? extends ChainHandler<? super T>> handlers) {
            Objects.requireNonNull(handlers, "handlers must not be null")
                    .forEach(this::add);
            return this;
        }

        public ResponsibilityChain<T> build() {
            if (handlers.isEmpty()) {
                throw new IllegalStateException(
                        "responsibility chain must contain at least one handler"
                );
            }
            List<ChainHandler<? super T>> orderedHandlers = new ArrayList<>(handlers);
            orderedHandlers.sort(Comparator.comparingInt(ChainHandler::order));
            return new ResponsibilityChain<T>(List.copyOf(orderedHandlers));
        }
    }
}
