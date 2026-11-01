package com.lzj.rag.backend.rag.service.impl;

import com.fasterxml.jackson.databind.*;
import com.lzj.rag.backend.knowledgebase.exception.KnowledgeBaseException;
import com.lzj.rag.backend.model.service.*;
import com.lzj.rag.backend.rag.citation.CitationValidator;
import com.lzj.rag.backend.rag.config.*;
import com.lzj.rag.backend.rag.prompt.PromptCatalog;
import com.lzj.rag.backend.rag.service.*;
import com.lzj.rag.backend.rag.service.bo.*;
import com.lzj.rag.backend.search.budget.RetrievalDeadline;
import com.lzj.rag.backend.search.service.bo.SearchBO;
import com.lzj.rag.backend.security.model.KnowledgeBasePrincipal;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

@Service
@EnableConfigurationProperties(AnswerProperties.class)
public class AnswerPipeline {
    private final RagRetrievalService retrieval;private final ConversationHistory history;private final EvidenceAssembler evidence;
    private final ChatClient model;private final StreamChatClient streamModel;private final TokenCounterClient tokens;private final CitationValidator citations;
    private final AnswerProperties config;private final ContextProperties context;private final ObjectMapper json;
    private final PromptCatalog.Template answerPrompt,repairPrompt;
    public AnswerPipeline(RagRetrievalService retrieval,ConversationHistory history,EvidenceAssembler evidence,ChatClient model,StreamChatClient streamModel,
            TokenCounterClient tokens,CitationValidator citations,AnswerProperties config,ContextProperties context,ObjectMapper json,PromptCatalog prompts) {
        this.retrieval=retrieval;this.history=history;this.evidence=evidence;this.model=model;this.streamModel=streamModel;this.tokens=tokens;this.citations=citations;
        this.config=config;this.context=context;this.json=json;
        if(config.maxOutputTokens()>context.outputReserve())throw new IllegalArgumentException("Answer output exceeds reserved context budget");
        // 启动时固定所选模板；错误配置不能在请求时悄悄换用其他版本。
        answerPrompt=prompts.get(prompts.answerVersion());repairPrompt=prompts.get(prompts.repairVersion());
    }

    public RagAnswerBO execute(AnswerTask task,RagRetrieveBO request,boolean streaming) throws java.io.IOException {
        var actor=task.actor;var run=task.run;var requestId=run.requestId();var deadline=run.deadline();
        String partial="";
        task.check();
            var scoped=new RagRetrieveBO(request.search(),run.conversationId());
            var result=retrieval.retrieve(actor,scoped,requestId,RagRetrievalService.QueryMode.USER,deadline);
            task.check();task.events.retrieval(result);task.events.sources(result.context());
            if(result.status().equals("UNAVAILABLE"))throw new KnowledgeBaseException(503,"RAG_RETRIEVAL_UNAVAILABLE","Evidence retrieval is incomplete");
            var sources=result.context()==null?List.<EvidenceSnapshotBO>of():result.context().evidence();
            task.checkpoint(new RagAnswerBO(run.id(),run.conversationId(),requestId,"RUNNING","",List.of(),sources,
                    result.query().degraded() || result.retrieval()!=null && result.retrieval().status().equals("DEGRADED"),"NOT_APPLICABLE",null,
                    result.status().equals("READY")?List.of(new RagAnswerBO.ModelCallBO("ANSWER_REQUESTED",config.model(),answerPrompt.version(),answerPrompt.sha256(),null)):List.of(),
                    result.query(),result.retrieval()==null?null:result.retrieval().trace(),result.context()==null?null:result.context().contentHash(),false,null));
            var calls=new ArrayList<RagAnswerBO.ModelCallBO>();
            boolean repaired=false;String status=result.status();String validation="NOT_APPLICABLE";
            List<RagAnswerBO.CitationBO> checked=List.of();
            if(status.equals("NEEDS_CLARIFICATION"))partial=result.query().clarificationQuestion();
            else if(status.equals("NO_EVIDENCE"))partial="当前授权资料中未找到可用证据，请补充资料或缩小问题范围。";
            else {
                var historyMessages=history.recent(actor,run.conversationId(),deadline);
                var data=new LinkedHashMap<String,Object>();data.put("question",request.search().query());data.put("authorizedHistory",historyMessages);
                data.put("evidence",json.readTree(result.context().modelContext()));
                var scope=scope(request,result);
                var messages=messages(actor,scope,sources,answerPrompt,data,requestId,deadline);
                task.check();
                JsonNode completion;
                if(streaming) {
                    completion=streamModel.stream(messages,config.model(),config.maxOutputTokens(),requestId,deadline,task,
                            text->task.append(text,config.maxAnswerChars()));
                    partial=task.partial();if(partial.isBlank())throw new KnowledgeBaseException(502,"RAG_MODEL_INVALID_RESPONSE","Model stream has no answer text");
                } else {
                    completion=generate(messages,requestId,deadline);partial=completion.path("content").asText();task.replace(partial);
                }
                calls.add(call("ANSWER",answerPrompt,completion));
                var check=citations.validate(partial,sources);
                if(!check.status().equals("VALID") && config.repairCitations() && !streaming) {
                    repaired=true;data.put("answer",partial);
                    try {
                        var repair=generate(messages(actor,scope,sources,repairPrompt,data,requestId,deadline),requestId,deadline);
                        calls.add(call("CITATION_REPAIR",repairPrompt,repair));partial=repair.path("content").asText();task.replace(partial);check=citations.validate(partial,sources);
                    } catch(KnowledgeBaseException repairFailed) {RetrievalDeadline.check(deadline);}
                }
                validation=check.status();checked=check.citations();status=validation.equals("VALID")?"COMPLETED":"CITATION_INVALID";
                evidence.revalidate(actor,scope,sources,deadline);
            }
            task.check();task.replace(partial);
            if(streaming && calls.isEmpty())task.events.message(partial);
            boolean degraded=result.query().degraded() || result.retrieval()!=null && result.retrieval().status().equals("DEGRADED") || status.equals("CITATION_INVALID");
            var response=new RagAnswerBO(run.id(),run.conversationId(),requestId,status,partial,checked,sources,degraded,validation,
                    calls.size()==1?calls.get(0).usage():null,List.copyOf(calls),result.query(),result.retrieval()==null?null:result.retrieval().trace(),
                    result.context()==null?null:result.context().contentHash(),repaired,status.equals("CITATION_INVALID")?"RAG_CITATION_INVALID":null);
            return response;
    }
    private List<Map<String,String>> messages(KnowledgeBasePrincipal actor,SearchBO scope,List<EvidenceSnapshotBO> sources,PromptCatalog.Template prompt,
                              Map<String,Object> data,String requestId,Instant deadline)throws java.io.IOException {
        RetrievalDeadline.check(deadline);
        var messages=List.of(Map.of("role","system","content",prompt.content()),Map.of("role","user","content",json.writeValueAsString(data)));
        String input=json.writeValueAsString(messages);
        var count=tokens.estimate(input,requestId,deadline);
        if(input.getBytes(StandardCharsets.UTF_8).length+context.outputReserve()+context.safetyReserve()>context.windowTokens()
                || Math.ceil(count.tokens()*1.25)+context.outputReserve()+context.safetyReserve()>context.windowTokens())
            throw new KnowledgeBaseException(422,"RAG_CONTEXT_BUDGET","Question, history and evidence exceed the generation input budget");
        evidence.revalidate(actor,scope,sources,deadline);
        return messages;
    }
    private JsonNode generate(List<Map<String,String>> messages,String requestId,Instant deadline) {
        var result=model.chat(messages,config.model(),false,config.maxOutputTokens(),requestId,deadline);
        if(result.path("content").asText().length()>config.maxAnswerChars())
            throw new KnowledgeBaseException(502,"RAG_MODEL_OUTPUT_BUDGET","Model output exceeds the answer limit");
        return result;
    }
    private SearchBO scope(RagRetrieveBO request,RagRetrievalBO result) {
        var r=request.search();return new SearchBO(result.query().effectiveQuery(),result.query().kbIds(),r.documentIds(),r.topK(),r.minScore(),r.strategy(),r.rerank(),r.minRerankScore());
    }
    private RagAnswerBO.ModelCallBO call(String purpose,PromptCatalog.Template prompt,JsonNode output) {
        return new RagAnswerBO.ModelCallBO(purpose,output.path("model").asText(),prompt.version(),prompt.sha256(),output.path("usage").isObject()?output.path("usage"):null);
    }
}
