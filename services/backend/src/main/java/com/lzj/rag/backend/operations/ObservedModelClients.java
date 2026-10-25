package com.lzj.rag.backend.operations;

import com.lzj.rag.backend.model.*;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.*;
import java.util.function.Supplier;

/** Counts complete logical calls, including invalid responses; no text, identity or raw provider error tags. */
@Configuration(proxyBeanMethods=false)
public class ObservedModelClients {
    @Bean @Primary EmbeddingClient observedEmbeddingClient(AiEmbeddingClient delegate,MeterRegistry registry) {
        registry.counter("kb.model.failures","operation","embedding");
        return (binding,texts,requestId,deadline)->observe(registry,"embedding",()->delegate.embed(binding,texts,requestId,deadline));
    }
    @Bean @Primary RerankClient observedRerankClient(AiRerankClient delegate,MeterRegistry registry) {
        registry.counter("kb.model.failures","operation","rerank");
        return (query,candidates,requestId,deadline)->observe(registry,"rerank",()->delegate.rerank(query,candidates,requestId,deadline));
    }
    private static <T> T observe(MeterRegistry registry,String operation,Supplier<T> call) {
        var sample=io.micrometer.core.instrument.Timer.start(registry);String outcome="success";
        try{return call.get();}
        catch(RuntimeException failure){outcome="failure";registry.counter("kb.model.failures","operation",operation).increment();throw failure;}
        finally{sample.stop(registry.timer("kb.model.calls","operation",operation,"outcome",outcome));}
    }
}
