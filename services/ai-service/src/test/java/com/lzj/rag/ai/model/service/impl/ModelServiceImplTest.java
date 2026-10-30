package com.lzj.rag.ai.model.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lzj.rag.ai.infra.provider.exception.ModelInvocationException;
import com.lzj.rag.ai.infra.provider.ProviderClient;
import com.lzj.rag.ai.model.resilience.RequestGate;
import com.lzj.rag.ai.model.routing.ModelRegistry;
import com.lzj.rag.ai.model.service.ModelService;
import com.lzj.rag.ai.model.service.bo.ChatBO;
import com.lzj.rag.ai.model.service.bo.EmbedBO;
import com.lzj.rag.ai.model.service.bo.RerankBO;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;
import java.util.List;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class ModelServiceImplTest {
    private final ProviderClient provider = mock(ProviderClient.class);
    private final ModelService service = new ModelServiceImpl(mock(ModelRegistry.class), provider,
            mock(RequestGate.class), new ObjectMapper());

    @Test
    void nonHttpCallersStillRejectAmbiguousChatAndEmbeddingInputs() {
        var chat = new ChatBO("text", List.of(), null, null, null, null, null, null, null, null);
        StepVerifier.create(service.chat(chat, false, "job-1")).expectErrorMatches(this::invalidInput).verify();
        StepVerifier.create(service.stream(chat, false, "job-1")).expectErrorMatches(this::invalidInput).verify();
        StepVerifier.create(service.embed(new EmbedBO("text", List.of("other"), null, null, null), "job-1"))
                .expectErrorMatches(this::invalidInput).verify();
        verifyNoInteractions(provider);
    }

    @Test
    void nonHttpCallersStillValidateOutputLimitsAndDocumentIdentity() {
        var chat = new ChatBO("text", null, null, null, null, null, null, -1, null, null);
        StepVerifier.create(service.chat(chat, false, "job-2")).expectErrorMatches(this::invalidInput).verify();
        var rerank = new RerankBO("query", List.of(new RerankBO.DocumentBO(null, "text")), 1, null);
        StepVerifier.create(service.rerank(rerank, "job-2")).expectErrorMatches(this::invalidInput).verify();
        verifyNoInteractions(provider);
    }

    private boolean invalidInput(Throwable error) {
        return error instanceof ModelInvocationException failure
                && failure.status() == 400 && failure.category().equals("INVALID_INPUT");
    }
}
