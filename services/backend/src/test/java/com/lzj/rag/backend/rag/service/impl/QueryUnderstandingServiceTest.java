package com.lzj.rag.backend.rag.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lzj.rag.backend.model.service.ChatClient;
import com.lzj.rag.backend.rag.config.QueryUnderstandingProperties;
import com.lzj.rag.backend.search.service.bo.SearchBO;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class QueryUnderstandingServiceTest {
    private final ObjectMapper json=new ObjectMapper();
    private final UUID kb=UUID.randomUUID();
    private QueryUnderstandingService service(boolean enabled,ChatClient client) {
        return new QueryUnderstandingService(new QueryUnderstandingProperties(enabled,"model",Duration.ofSeconds(1),3,512,Map.of("PG","PostgreSQL")),client,json,
                new com.lzj.rag.backend.rag.prompt.PromptCatalog(new com.lzj.rag.backend.rag.prompt.PromptProperties(null,"query-understanding-v1")),
                new com.lzj.rag.backend.search.config.SearchProperties(50,16,100,4000,Duration.ofSeconds(40)));
    }
    private SearchBO request(String text) { return new SearchBO(text,List.of(kb),null,5,null); }
    private com.fasterxml.jackson.databind.JsonNode output(String effective,List<String> subs,boolean clarify,String question,List<UUID> ids)throws Exception {
        var plan=new LinkedHashMap<String,Object>();plan.put("effectiveQuery",effective);plan.put("subQueries",subs);
        plan.put("needsClarification",clarify);plan.put("clarificationQuestion",question);plan.put("kbIds",ids);
        return json.valueToTree(Map.of("content",json.writeValueAsString(plan)));
    }
    @Test void deterministicTermsKeepIdentifiersNegationAndNumbersWithoutCallingTheModel() {
        var service=service(false,(a,b,c,d,e,f)->{throw new AssertionError("Model must stay disabled");});
        var plan=service.understand(request("PG 不支持 ZX-1472，最多 30 天"),List.of(),"test",Instant.now().plusSeconds(5));
        assertThat(plan.effectiveQuery()).isEqualTo("PostgreSQL 不支持 ZX-1472，最多 30 天");
        assertThat(plan.degraded()).isFalse();
    }
    @Test void structuredRewriteAndActualClarificationHaveDistinctStates()throws Exception {
        var good=output("PostgreSQL 备份",List.of("PostgreSQL 如何备份","PostgreSQL 如何备份"),false,null,List.of(kb));
        var plan=service(true,(a,b,c,d,e,f)->good).understand(request("PG 备份"),List.of(),"test",Instant.now().plusSeconds(5));
        assertThat(plan.subQueries()).containsExactly("PostgreSQL 如何备份");assertThat(plan.degraded()).isFalse();
        var clarify=output("如何申请",List.of(),true,"你指的是哪项服务？",List.of(kb));
        assertThat(service(true,(a,b,c,d,e,f)->clarify).understand(request("这个如何申请"),List.of(),"test",Instant.now().plusSeconds(5)).needsClarification()).isTrue();
    }
    @Test void badJsonTooManyQuestionsChangedConstraintsAndUnauthorizedRoutingFallBack()throws Exception {
        var values=List.of(json.valueToTree(Map.of("content","{bad")),
                output("PostgreSQL 30 天",List.of("PostgreSQL 30 天"),false,null,List.of(kb)),
                output("PostgreSQL 不支持 30 天",List.of("一","二","三","四"),false,null,List.of(kb)),
                output("PostgreSQL 不支持 30 天",List.of("PostgreSQL 不支持 30 天"),false,null,List.of(UUID.randomUUID())));
        for(var value:values) {
            var plan=service(true,(a,b,c,d,e,f)->value).understand(request("PG 不支持 30 天"),List.of(),"test",Instant.now().plusSeconds(5));
            assertThat(plan.degraded()).isTrue();assertThat(plan.needsClarification()).isFalse();
            assertThat(plan.effectiveQuery()).isEqualTo("PostgreSQL 不支持 30 天");assertThat(plan.kbIds()).containsExactly(kb);
        }
        var plan=service(true,(a,b,c,d,e,f)->{throw new com.lzj.rag.backend.knowledgebase.exception.KnowledgeBaseException(504,"KB_MODEL_TIMEOUT","secret");})
                .understand(request("PG"),List.of(),"test",Instant.now().plusSeconds(5));
        assertThat(plan.fallbackReason()).isEqualTo("KB_MODEL_TIMEOUT");
    }

    @Test void scopeAndConversationOwnershipPrecedeUnderstandingAndIndependentModeSkipsHistory() {
        var engine=org.mockito.Mockito.mock(com.lzj.rag.backend.search.service.HybridRetrievalService.class);
        var history=org.mockito.Mockito.mock(com.lzj.rag.backend.rag.service.ConversationHistory.class);
        var understanding=org.mockito.Mockito.mock(QueryUnderstandingService.class);
        var actor=new com.lzj.rag.backend.security.model.KnowledgeBasePrincipal("alice","unused","tenant","alice");
        var input=new com.lzj.rag.backend.rag.service.bo.RagRetrieveBO(request("database"),UUID.randomUUID());
        var deadline=Instant.now().plusSeconds(5);
        var denied=new com.lzj.rag.backend.knowledgebase.exception.KnowledgeBaseException(404,"KB_NOT_FOUND","not found");
        org.mockito.Mockito.when(engine.prepare(actor,input.search(),deadline)).thenThrow(denied);
        var facade=new RagRetrievalServiceImpl(engine,understanding,history,org.mockito.Mockito.mock(EvidenceAssembler.class));
        assertThatThrownBy(()->facade.retrieve(actor,input,"test",com.lzj.rag.backend.rag.service.RagRetrievalService.QueryMode.USER,deadline)).isSameAs(denied);
        org.mockito.Mockito.verifyNoInteractions(understanding,history);
        org.mockito.Mockito.doReturn(input.search()).when(engine).prepare(actor,input.search(),deadline);
        org.mockito.Mockito.when(history.recent(actor,input.conversationId(),deadline)).thenThrow(denied);
        assertThatThrownBy(()->facade.retrieve(actor,input,"test",com.lzj.rag.backend.rag.service.RagRetrievalService.QueryMode.USER,deadline)).isSameAs(denied);
        org.mockito.Mockito.verifyNoInteractions(understanding);
        org.mockito.Mockito.when(understanding.understand(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.eq(List.of()),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any())).thenThrow(new IllegalStateException("reached independent query"));
        assertThatThrownBy(()->facade.retrieve(actor,input,"test",com.lzj.rag.backend.rag.service.RagRetrievalService.QueryMode.INDEPENDENT,deadline)).hasMessage("reached independent query");
        org.mockito.Mockito.verify(history,org.mockito.Mockito.times(1)).recent(actor,input.conversationId(),deadline);
    }
}
