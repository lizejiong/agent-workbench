package com.lzj.rag.ai.resilience;

import com.lzj.rag.ai.config.ModelRegistry;
import com.lzj.rag.ai.service.AiException;
import org.springframework.stereotype.Component;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.*;

/** Process-local admission and generation-checked circuit leases; no background probes. */
@Component
public class RequestGate {
    private final ModelRegistry registry;
    private final AtomicInteger active = new AtomicInteger();
    private final ConcurrentHashMap<String, Circuit> circuits = new ConcurrentHashMap<>();
    public RequestGate(ModelRegistry registry) { this.registry = registry; }
    public AutoCloseable enter() {
        if (active.incrementAndGet() > registry.config().limits().concurrency()) {
            active.decrementAndGet();
            throw new AiException(429, "SATURATED", "AI service is busy", false, false);
        }
        return active::decrementAndGet;
    }
    public Lease acquire(String alias) { return circuits.computeIfAbsent(alias, a -> new Circuit()).acquire(); }
    private final class Circuit {
        long generation, until;
        int failures;
        boolean open, probing;
        synchronized Lease acquire() {
            if (open && (System.nanoTime() < until || probing)) throw AiException.unavailable("Model circuit is open");
            boolean probe = open;
            if (probe) probing = true;
            return new Lease(this, generation, probe);
        }
        synchronized void finish(long leaseGeneration, boolean probe, Boolean success, boolean counted) {
            if (leaseGeneration != generation) return;
            if (probe) probing = false;
            if (Boolean.TRUE.equals(success)) {
                failures = 0;
                if (probe) { open = false; generation++; }
            } else if (counted && (probe || ++failures >= registry.config().limits().circuitFailures())) {
                open = true; probing = false; generation++;
                until = System.nanoTime() + registry.config().limits().circuitCooldown().toNanos();
            }
        }
    }
    public static final class Lease implements AutoCloseable {
        private final Circuit circuit;
        private final long generation;
        private final boolean probe;
        private final AtomicBoolean completed = new AtomicBoolean();
        private Lease(Circuit circuit, long generation, boolean probe) {
            this.circuit = circuit; this.generation = generation; this.probe = probe;
        }
        public void success() { finish(true, false); }
        public void failure(boolean counted) { finish(false, counted); }
        private void finish(Boolean success, boolean counted) {
            if (completed.compareAndSet(false, true)) circuit.finish(generation, probe, success, counted);
        }
        @Override public void close() { finish(null, false); }
    }
}
