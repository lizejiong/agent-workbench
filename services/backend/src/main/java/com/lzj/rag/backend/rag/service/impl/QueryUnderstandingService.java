package com.lzj.rag.backend.rag.service.impl;

import com.fasterxml.jackson.databind.*;
import com.lzj.rag.backend.knowledgebase.exception.KnowledgeBaseException;
import com.lzj.rag.backend.model.service.ChatClient;
import com.lzj.rag.backend.rag.config.QueryUnderstandingProperties;
import com.lzj.rag.backend.rag.prompt.PromptCatalog;
import com.lzj.rag.backend.rag.service.bo.QueryPlanBO;
import com.lzj.rag.backend.search.budget.RetrievalDeadline;
import com.lzj.rag.backend.search.service.bo.SearchBO;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;

@Service
@EnableConfigurationProperties(QueryUnderstandingProperties.class)
public class QueryUnderstandingService {
    private static final Set<String> FIELDS = Set.of("effectiveQuery", "subQueries", "needsClarification", "clarificationQuestion", "kbIds");
    private static final Pattern PROTECTED = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/:-]*|不|未|无|非|禁止|除外|至少|最多|元|万元|公斤|毫升|小时|天|%|≥|≤");
    private final QueryUnderstandingProperties config;
    private final ChatClient model;
    private final ObjectMapper json;
    private final PromptCatalog.Template prompt;
    private final String termVersion;
    private final com.lzj.rag.backend.search.config.SearchProperties limits;

    public QueryUnderstandingService(QueryUnderstandingProperties config, ChatClient model, ObjectMapper json, PromptCatalog prompts,
                                     com.lzj.rag.backend.search.config.SearchProperties limits) {
        this.config=config; this.model=model; this.json=json;
        this.limits=limits;
        prompt=prompts.query();
        try {
            termVersion=com.lzj.rag.backend.model.catalog.EmbeddingCatalog.sha256(json.writeValueAsString(new TreeMap<>(config.terms())));
        } catch(java.io.IOException e) { throw new IllegalStateException("Missing query prompt", e); }
    }

    public QueryPlanBO understand(SearchBO scope, List<Map<String,String>> history, String requestId, Instant deadline) {
        String original=scope.query().strip();
        String normalized=original;
        for(var term:config.terms().entrySet().stream().sorted(Comparator.<Map.Entry<String,String>>comparingInt(e -> e.getKey().length()).reversed().thenComparing(Map.Entry::getKey)).toList()) {
            String key=term.getKey();
            String expression=(key.matches("[A-Za-z0-9_-]+") ? "(?<![A-Za-z0-9_-])" : "")+Pattern.quote(key)
                    +(key.matches("[A-Za-z0-9_-]+") ? "(?![A-Za-z0-9_-])" : "");
            normalized=normalized.replaceAll(expression, java.util.regex.Matcher.quoteReplacement(term.getValue()));
        }
        if (normalized.codePointCount(0,normalized.length())>limits.maxQueryChars()) normalized=original;
        if (!config.enabled() || scope.kbIds().isEmpty()) return plain(original,normalized,scope.kbIds(),false,null);
        RetrievalDeadline.check(deadline);
        try {
            var data=Map.of("query", normalized, "authorizedHistory",history,"allowedKbIds",scope.kbIds());
            var result=model.chat(List.of(Map.of("role","system","content",prompt.content()),Map.of("role","user","content",json.writeValueAsString(data))),
                    config.model(),true,config.maxOutputTokens(),requestId, earlier(deadline,Instant.now().plus(config.timeout())));
            JsonNode output;
            try(var parser=json.getFactory().createParser(result.path("content").asText())) {
                parser.enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
                output=json.readTree(parser);
                if(parser.nextToken()!=null) throw invalid();
            }
            if(output==null || !output.isObject()) throw invalid();
            var names=new HashSet<String>();output.fieldNames().forEachRemaining(names::add);
            if(!FIELDS.containsAll(names) || !output.path("effectiveQuery").isTextual() || !output.path("needsClarification").isBoolean()
                    || !output.path("subQueries").isArray() || output.path("subQueries").size()>config.maxSubQueries()
                    || !output.path("kbIds").isArray()) throw invalid();
            String effective=checked(output.path("effectiveQuery").asText());
            var queries=new LinkedHashSet<String>();
            for(var q:output.path("subQueries")) { if(!q.isTextual()) throw invalid();queries.add(checked(q.asText())); }
            var ids=new LinkedHashSet<UUID>();
            for(var id:output.path("kbIds")) {
                if(!id.isTextual()) throw invalid();UUID value=UUID.fromString(id.asText());
                if(!scope.kbIds().contains(value)) throw invalid();ids.add(value);
            }
            boolean clarify=output.path("needsClarification").asBoolean();
            String question=output.path("clarificationQuestion").isTextual() ? output.path("clarificationQuestion").asText().strip() : null;
            if(clarify && (question==null || question.isBlank() || question.length()>512 || !queries.isEmpty())) throw invalid();
            if(!clarify && (queries.isEmpty() || !preserves(normalized,effective) || !preserves(normalized,String.join(" ",queries)))) throw invalid();
            return new QueryPlanBO(original,effective,List.copyOf(queries),List.copyOf(ids),clarify,clarify?question:null,false,null,prompt.version(),
                    result.path("model").isTextual()?result.path("model").asText():config.model(),termVersion,prompt.sha256());
        } catch (Exception failure) {
            if(Thread.currentThread().isInterrupted()) throw RetrievalDeadline.cancelled();
            RetrievalDeadline.check(deadline);
            String code=failure instanceof KnowledgeBaseException kb ? kb.code() : "RAG_QUERY_INVALID_OUTPUT";
            return plain(original,normalized,scope.kbIds(),true,code);
        }
    }

    private QueryPlanBO plain(String original,String query,List<UUID> ids,boolean degraded,String reason) {
        return new QueryPlanBO(original,query,List.of(query),List.copyOf(ids),false,null,degraded,reason,prompt.version(),null,termVersion,prompt.sha256());
    }
    private String checked(String text) {
        String value=text.strip();
        if(value.isBlank() || value.codePointCount(0,value.length())>limits.maxQueryChars() || value.codePoints().anyMatch(c -> c==0 || c>=0xd800 && c<=0xdfff)) throw invalid();
        return value;
    }
    private static boolean preserves(String from,String to) {
        var matcher=PROTECTED.matcher(from);
        var target=new HashSet<String>();var after=PROTECTED.matcher(to);
        while(after.find()) target.add(after.group());
        while(matcher.find()) if(!target.contains(matcher.group())) return false;
        return true;
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid structured query plan"); }
    private static Instant earlier(Instant a,Instant b) { return a.isBefore(b)?a:b; }
}
