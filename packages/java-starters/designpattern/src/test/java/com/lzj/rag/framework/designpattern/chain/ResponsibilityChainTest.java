package com.lzj.rag.framework.designpattern.chain;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResponsibilityChainTest {

    @Test
    void shouldExecuteHandlersByOrderAndPreserveInsertionOrderForTies() {
        List<String> visits = new ArrayList<>();
        ResponsibilityChain<String> chain = ResponsibilityChain.<String>builder()
                .add(handler("third", 20, ChainDecision.CONTINUE, visits))
                .add(handler("first", 10, ChainDecision.CONTINUE, visits))
                .add(handler("second", 10, ChainDecision.CONTINUE, visits))
                .build();

        ChainDecision result = chain.execute("request");

        assertThat(result).isEqualTo(ChainDecision.CONTINUE);
        assertThat(visits).containsExactly("first:request", "second:request", "third:request");
    }

    @Test
    void shouldStopWithoutCallingRemainingHandlers() {
        List<String> visits = new ArrayList<>();
        ResponsibilityChain<String> chain = ResponsibilityChain.<String>builder()
                .add(handler("validation", 10, ChainDecision.CONTINUE, visits))
                .add(handler("rejected", 20, ChainDecision.STOP, visits))
                .add(handler("never", 30, ChainDecision.CONTINUE, visits))
                .build();

        ChainDecision result = chain.execute("request");

        assertThat(result).isEqualTo(ChainDecision.STOP);
        assertThat(visits).containsExactly("validation:request", "rejected:request");
    }

    @Test
    void shouldRejectEmptyChainAndNullHandler() {
        assertThatThrownBy(() -> ResponsibilityChain.<String>builder().build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("responsibility chain must contain at least one handler");

        assertThatThrownBy(() -> ResponsibilityChain.<String>builder().add(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("handler must not be null");
    }

    @Test
    void shouldRejectNullRequestAndNullHandlerDecision() {
        ResponsibilityChain<String> chain = ResponsibilityChain.<String>builder()
                .add(request -> null)
                .build();

        assertThatThrownBy(() -> chain.execute(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("request must not be null");
        assertThatThrownBy(() -> chain.execute("request"))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("handler decision must not be null");
    }

    private ChainHandler<String> handler(
            String name,
            int order,
            ChainDecision decision,
            List<String> visits) {
        return new ChainHandler<>() {
            @Override
            public ChainDecision handle(String request) {
                visits.add(name + ":" + request);
                return decision;
            }

            @Override
            public int order() {
                return order;
            }
        };
    }
}
