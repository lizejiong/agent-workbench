package com.lzj.rag.framework.designpattern.strategy;

/**
 * A named, interchangeable business rule.
 *
 * @param <REQUEST> request type
 * @param <RESPONSE> response type
 */
public interface Strategy<REQUEST, RESPONSE> {

    String mark();

    RESPONSE execute(REQUEST request);
}
