package com.lzj.rag.ai.model.resilience;

import com.lzj.rag.ai.infra.provider.exception.ModelInvocationException;
import com.lzj.rag.ai.model.routing.ModelRegistry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.*;
import org.springframework.stereotype.Component;

/**
 * 进程内并发入口与按逻辑模型维护的熔断器。
 */
@Component
public class RequestGate {

    /**
     * 模型注册器，提供配置快照、候选路由和能力检查。
     */
    private final ModelRegistry registry;

    /**
     * 当前已占用并发名额的逻辑请求数。
     */
    private final AtomicInteger active = new AtomicInteger();

    /**
     * 按逻辑模型别名保存的进程内熔断状态表。
     */
    private final ConcurrentHashMap<String, Circuit> circuits = new ConcurrentHashMap<>();

    /**
     * 注入注册器，用于读取进程内并发和熔断限制。
     *
     * @param registry 模型注册器，提供配置快照、候选路由和能力检查。
     */
    public RequestGate(ModelRegistry registry) {
        this.registry = registry;
    }

    /**
     * 申请整个逻辑请求的并发名额；超过上限时立即回滚计数并拒绝请求。
     * @return 关闭时释放名额的许可。
     */
    public AutoCloseable enter() {
        if (active.incrementAndGet() > registry.config().limits().concurrency()) {
            active.decrementAndGet();
            throw new ModelInvocationException(429, "SATURATED", "AI service is busy", false, false);
        }
        return active::decrementAndGet;
    }

    /**
     * 查找或创建指定逻辑模型的熔断器，并申请本次尝试的租约。
     *
     * @param alias 应用内部的逻辑模型别名，不是供应商原生模型名。
     * @return 当前代次的调用租约。
     */
    public Lease acquire(String alias) {
        // a：当前待检查或创建熔断状态的逻辑模型别名。
        return circuits.computeIfAbsent(alias, a -> new Circuit()).acquire();
    }

    /**
     * 单个逻辑模型的熔断状态；所有状态迁移都在同一对象锁内完成。
     */
    private final class Circuit {

        /**
         * 熔断状态代次，用于拒绝旧请求覆盖新状态。
         */
        long generation;

        /**
         * 按单调时钟计量的熔断冷却截止时间，单位为纳秒。
         */
        long until;

        /**
         * 当前代次连续发生的计数失败次数。
         */
        int failures;

        /**
         * 熔断器当前是否处于打开状态。
         */
        boolean open;

        /**
         * 当前是否已经有一个请求占用了半开探测名额。
         */
        boolean probing;

        /**
         * 在对象锁内判断熔断状态，冷却后只放行一个真实请求作为半开探测。
         * @return 携带当前代次和探测标记的租约。
         */
        synchronized Lease acquire() {
            if (open && (System.nanoTime() < until || probing))
                throw ModelInvocationException.unavailable("Model circuit is open");
            // 当前租约是否代表冷却后的半开探测。
            boolean probe = open;
            if (probe)
                probing = true;
            return new Lease(this, generation, probe);
        }

        /**
         * 按租约代次处理成功、计数失败或取消；旧并发请求不能改变新一代熔断状态。
         *
         * @param leaseGeneration 租约取得时的熔断代次，只能结算对应代次。
         * @param probe 当前租约是否代表冷却后的半开探测。
         * @param success 结算结果：true 为成功、false 为失败、null 为取消或仅释放。
         * @param counted 本次失败是否计入模型熔断失败次数。
         */
        synchronized void finish(long leaseGeneration, boolean probe, Boolean success, boolean counted) {
            // 先拒绝旧代次结算，避免历史请求成功后错误关闭刚打开的熔断器。
            if (leaseGeneration != generation)
                return;

            // 成功、失败或取消都要归还半开名额；取消本身不增加失败次数。
            if (probe)
                probing = false;

            // 成功清零失败计数；当前半开探测成功后才进入新的关闭代次。
            if (Boolean.TRUE.equals(success)) {
                failures = 0;
                if (probe) {
                    open = false;
                    generation++;
                }
            // 只有计数失败才可能重新打开熔断，并从当前时刻计算新一轮冷却时间。
            } else if (counted && (probe || ++failures >= registry.config().limits().circuitFailures())) {
                open = true;
                probing = false;
                generation++;
                until = System.nanoTime() + registry.config().limits().circuitCooldown().toNanos();
            }
        }
    }

    /**
     * 一次模型尝试持有的熔断租约，携带取得时的代次，且只允许完成一次。
     */
    public static final class Lease implements AutoCloseable {

        /**
         * 当前租约所属的单模型熔断对象。
         */
        private final Circuit circuit;

        /**
         * 熔断状态代次，用于拒绝旧请求覆盖新状态。
         */
        private final long generation;

        /**
         * 当前租约是否代表冷却后的半开探测。
         */
        private final boolean probe;

        /**
         * 租约是否已结算的原子标记，防止重复关闭和重复计数。
         */
        private final AtomicBoolean completed = new AtomicBoolean();

        /**
         * 保存取得租约时的熔断对象、代次和半开探测标记。
         *
         * @param circuit 当前租约所属的单模型熔断对象。
         * @param generation 熔断状态代次，用于拒绝旧请求覆盖新状态。
         * @param probe 当前租约是否代表冷却后的半开探测。
         */
        private Lease(Circuit circuit, long generation, boolean probe) {
            this.circuit = circuit;
            this.generation = generation;
            this.probe = probe;
        }

        /**
         * 把本次租约标记为成功；成功的半开探测会关闭熔断器。
         */
        public void success() {
            finish(true, false);
        }

        /**
         * 按错误分类决定是否增加熔断失败次数。
         *
         * @param counted 本次失败是否计入模型熔断失败次数。
         */
        public void failure(boolean counted) {
            finish(false, counted);
        }

        /**
         * 使用原子标志保证回调和资源关闭并发发生时只结算一次。
         *
         * @param success 结算结果：true 为成功、false 为失败、null 为取消或仅释放。
         * @param counted 本次失败是否计入模型熔断失败次数。
         */
        private void finish(Boolean success, boolean counted) {
            if (completed.compareAndSet(false, true))
                circuit.finish(generation, probe, success, counted);
        }

        /**
         * 释放尚未结算的租约；取消不算失败，但必须释放半开探测名额。
         */
        @Override
        public void close() {
            finish(null, false);
        }
    }
}
