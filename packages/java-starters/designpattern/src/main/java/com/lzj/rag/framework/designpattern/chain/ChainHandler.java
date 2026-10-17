package com.lzj.rag.framework.designpattern.chain;

/**
 * Handles one step in a responsibility chain.
 *
 * @param <T> request type handled by the chain
 */
@FunctionalInterface
public interface ChainHandler<T> {

    ChainDecision handle(T request);

    default int order() {
        return 0;
    }
}
