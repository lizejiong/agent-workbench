package com.lzj.rag.ai.model.resilience;

import com.lzj.rag.ai.infra.provider.exception.ModelInvocationException;
import com.lzj.rag.ai.model.config.AiProperties;
import com.lzj.rag.ai.model.routing.ModelRegistry;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RequestGateTest {
    @Test
    void staleSuccessCannotCloseCircuitAndCancelledProbeReleasesLease() {
        ModelRegistry registry = mock(ModelRegistry.class);
        AiProperties config = mock(AiProperties.class);
        AiProperties.Limits limits = mock(AiProperties.Limits.class);
        when(registry.config()).thenReturn(config);
        when(config.limits()).thenReturn(limits);
        when(limits.circuitFailures()).thenReturn(3);
        when(limits.circuitCooldown()).thenReturn(Duration.ZERO);
        RequestGate gate = new RequestGate(registry);

        var stale = gate.acquire("qwen-chat");
        for (int i = 0; i < 3; i++) gate.acquire("qwen-chat").failure(true);
        stale.success();
        var probe = gate.acquire("qwen-chat");
        assertThatThrownBy(() -> gate.acquire("qwen-chat")).isInstanceOf(ModelInvocationException.class);
        probe.close();
        gate.acquire("qwen-chat").success();
        // A successful probe closes the circuit and allows concurrent ordinary leases.
        try (var first = gate.acquire("qwen-chat"); var second = gate.acquire("qwen-chat")) {
            first.success();
            second.success();
        }
    }
}
