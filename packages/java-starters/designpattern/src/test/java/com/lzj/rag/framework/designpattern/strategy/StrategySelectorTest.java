package com.lzj.rag.framework.designpattern.strategy;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StrategySelectorTest {

    @Test
    void shouldExecuteStrategyByMark() {
        Strategy<Integer, String> highSpeed = strategy("high-speed", "G");
        Strategy<Integer, String> regular = strategy("regular", "K");
        StrategySelector<Integer, String> selector = new StrategySelector<>(List.of(highSpeed, regular));

        assertThat(selector.execute("high-speed", 123)).isEqualTo("G123");
        assertThat(selector.execute("regular", 45)).isEqualTo("K45");
    }

    @Test
    void shouldRejectUnknownStrategyMark() {
        StrategySelector<Integer, String> selector = new StrategySelector<>(List.of(strategy("high-speed", "G")));

        assertThatThrownBy(() -> selector.execute("missing", 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("strategy not found: missing");
    }

    @Test
    void shouldRejectDuplicateAndNullStrategyMarks() {
        assertThatThrownBy(() -> new StrategySelector<>(List.of(
                strategy("same", "G"),
                strategy("same", "K")
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("duplicate strategy mark: same");

        assertThatThrownBy(() -> new StrategySelector<>(List.of(strategy(null, "G"))))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("strategy mark must not be null");
    }

    private Strategy<Integer, String> strategy(String mark, String prefix) {
        return new Strategy<>() {
            @Override
            public String mark() {
                return mark;
            }

            @Override
            public String execute(Integer input) {
                return prefix + input;
            }
        };
    }
}
