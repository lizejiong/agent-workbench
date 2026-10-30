package com.lzj.rag.backend.operations.config;

import com.lzj.rag.backend.model.client.AiEmbeddingClient;
import com.lzj.rag.backend.model.client.AiRerankClient;
import com.lzj.rag.backend.model.service.EmbeddingClient;
import com.lzj.rag.backend.model.service.RerankClient;

import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.context.annotation.*;

import java.util.function.Supplier;

/** 为模型客户端增加耗时和结果指标，保留原有异常与业务结果。 */
@Configuration(proxyBeanMethods = false)
public class ObservedModelClients {

    /**
     * 为嵌入调用装配耗时与结果指标装饰器。
     *
     * @param delegate 复用受限 HTTP 传输的 AI 嵌入客户端。
     * @param registry 记录模型调用及后台任务指标的注册器。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @Bean
    @Primary
    EmbeddingClient observedEmbeddingClient(AiEmbeddingClient delegate, MeterRegistry registry) {
        registry.counter("kb.model.failures", "operation", "embedding");
        // binding：本次业务固定使用的嵌入模型身份与配置快照。
        // texts：本次需要计算嵌入的有序文本集合。
        // requestId：请求跟踪标识或业务幂等键，按当前接口约定使用。
        // deadline：本阶段或本次请求必须完成的截止时间。
        return (binding, texts, requestId, deadline) ->
                observe(
                        registry,
                        "embedding",
                        () -> delegate.embed(binding, texts, requestId, deadline));
    }

    /**
     * 为重排调用装配耗时与结果指标装饰器。
     *
     * @param delegate 未增加指标装饰的 AI 重排客户端。
     * @param registry 记录模型调用及后台任务指标的注册器。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @Bean
    @Primary
    RerankClient observedRerankClient(AiRerankClient delegate, MeterRegistry registry) {
        registry.counter("kb.model.failures", "operation", "rerank");
        // query：检索查询文本或已生成的查询向量。
        // candidates：本次待领取的任务、可选解析策略或召回候选集合。
        // requestId：请求跟踪标识或业务幂等键，按当前接口约定使用。
        // deadline：本阶段或本次请求必须完成的截止时间。
        return (query, candidates, requestId, deadline) ->
                observe(
                        registry,
                        "rerank",
                        () -> delegate.rerank(query, candidates, requestId, deadline));
    }

    /**
     * 记录一次模型操作的耗时及成功或失败结果，并原样传播业务异常。
     *
     * @param registry 记录模型调用及后台任务指标的注册器。
     * @param operation 持久化上传操作、操作标识或审计动作名称。
     * @param call 需要增加耗时与结果指标的模型调用。
     * @param <T> 当前业务结果或输入元素的类型。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private static <T> T observe(MeterRegistry registry, String operation, Supplier<T> call) {
        // sample：当前模型调用的指标计时样本。
        var sample = io.micrometer.core.instrument.Timer.start(registry);
        // outcome：检索结果的证据确定性分类。
        String outcome = "success";
        try {
            return call.get();
        } catch (RuntimeException failure) {
            // failure：当前需要传播或记录的业务失败。
            outcome = "failure";
            registry.counter("kb.model.failures", "operation", operation).increment();
            throw failure;
        } finally {
            sample.stop(
                    registry.timer("kb.model.calls", "operation", operation, "outcome", outcome));
        }
    }
}
