package com.lzj.rag.framework.designpattern.strategy;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Selects and executes strategies by business mark.
 *
 * @param <REQUEST> request type shared by registered strategies
 * @param <RESPONSE> response type shared by registered strategies
 */
public final class StrategySelector<REQUEST, RESPONSE> {

    private final Map<String, Strategy<REQUEST, RESPONSE>> strategies = new LinkedHashMap<>();

    public StrategySelector(Collection<? extends Strategy<REQUEST, RESPONSE>> strategies) {
        Objects.requireNonNull(strategies, "strategy collection must not be null");
        for (Strategy<REQUEST, RESPONSE> strategy : strategies) {
            Objects.requireNonNull(strategy, "strategy must not be null");
            String mark = Objects.requireNonNull(strategy.mark(), "strategy mark must not be null");
            if (this.strategies.putIfAbsent(mark, strategy) != null) {
                throw new IllegalArgumentException("duplicate strategy mark: " + mark);
            }
        }
    }

    public RESPONSE execute(String mark, REQUEST request) {
        Strategy<REQUEST, RESPONSE> strategy = strategies.get(mark);
        if (strategy == null) {
            throw new IllegalArgumentException("strategy not found: " + mark);
        }
        return strategy.execute(request);
    }
}
