package com.lzj.rag.backend.search;

import com.lzj.rag.backend.model.RerankClient;
import com.lzj.rag.backend.security.KnowledgeBasePrincipal;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static com.lzj.rag.backend.search.KnowledgeSearchTypes.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RerankDeadlineTest {
    private final RerankClient model=mock(RerankClient.class);
    private final EvidenceRepository records=mock(EvidenceRepository.class);
    private final KnowledgeBasePrincipal actor=new KnowledgeBasePrincipal("alice","unused","a","alice");
    private final UUID kb=UUID.randomUUID(),chunk=UUID.randomUUID();
    private final Request request=new Request("database",List.of(kb),null,5,null,"HYBRID",true,null);
    private final Evidence evidence=new Evidence(chunk,kb,UUID.randomUUID(),"guide",0,1,"Authorized source",0.9,"COSINE_SIMILARITY",Map.of(),"/api/documents/id/file");
    private final List<SearchChannel.Candidate> candidates=List.of(new SearchChannel.Candidate(evidence,"VECTOR",1,0.9,"COSINE_SIMILARITY",0,null));
    private RrfRerankPostProcessor processor() {
        var config=new HybridProperties("AUTO",50,20,60,1,1,2,Duration.ofSeconds(2),Duration.ofSeconds(1),new HybridProperties.Rerank(true,"qwen-rerank",Duration.ofSeconds(1),30000,null));
        return new RrfRerankPostProcessor(config,model,records);
    }
    @Test void revokedEvidenceNeverReachesTheModel() {
        when(records.hydrate(any(),any(),anyCollection())).thenReturn(Map.of());
        var result=processor().process(actor,request,candidates,"denied",true,20,Instant.now().plusSeconds(1));
        assertThat(result.evidence()).isEmpty();assertThat(result.rerankStatus()).isEqualTo("SKIPPED_EMPTY");verifyNoInteractions(model);
    }
    @Test void forwardsTheRemainingBudgetAndRechecksEvidenceAfterTheModelWait() {
        Instant deadline=Instant.now().plusMillis(500);
        when(records.hydrate(any(),any(),anyCollection())).thenReturn(Map.of(chunk,evidence),Map.of());
        when(model.rerank(anyString(),anyList(),anyString(),any())).thenAnswer(call->{
            assertThat((Instant)call.getArgument(3)).isEqualTo(deadline);
            assertThat(call.<List<RerankClient.Input>>getArgument(1)).containsExactly(new RerankClient.Input(chunk,evidence.content()));
            return List.of(new RerankClient.Ranked(0,0.8));
        });
        assertThat(processor().process(actor,request,candidates,"revoked-during-wait",true,20,deadline).evidence()).isEmpty();
    }
    @Test void expiredTotalBudgetIsAnErrorAndCannotBeSwallowedAsRerankFallback() {
        when(records.hydrate(any(),any(),anyCollection())).thenReturn(Map.of(chunk,evidence));
        when(model.rerank(anyString(),anyList(),anyString(),any())).thenAnswer(call->{Thread.sleep(120);throw new IllegalStateException();});
        assertThatThrownBy(()->processor().process(actor,request,candidates,"expired",true,20,Instant.now().plusMillis(80)))
                .isInstanceOfSatisfying(com.lzj.rag.backend.knowledgebase.KnowledgeBaseException.class,e->assertThat(e.code()).isEqualTo("KB_SEARCH_TIMEOUT"));
    }
}
