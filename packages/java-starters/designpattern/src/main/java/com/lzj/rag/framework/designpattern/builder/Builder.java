package com.lzj.rag.framework.designpattern.builder;

/**
 * Creates a fully configured object.
 *
 * @param <T> object type produced by the builder
 */
@FunctionalInterface
public interface Builder<T> {

    T build();
}
