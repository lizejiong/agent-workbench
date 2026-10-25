package com.lzj.rag.backend.search;

import com.fasterxml.jackson.databind.*;
import com.lzj.rag.backend.document.DocumentService;
import com.lzj.rag.backend.knowledgebase.*;
import com.lzj.rag.backend.model.EmbeddingClient;
import com.lzj.rag.backend.security.KnowledgeBasePrincipal;
import com.lzj.rag.backend.vector.VectorStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;

import static com.lzj.rag.backend.search.KnowledgeSearchTypes.*;

@Service
@EnableConfigurationProperties(SearchProperties.class)
public class KnowledgeSearchService {
    private final KnowledgeBaseService bases;private final DocumentService documents;private final EmbeddingClient embeddings;
    private final VectorStore vectors;private final ObjectMapper json;private final SearchProperties config;
    private static final Set<String> SOURCE_FIELDS=Set.of("chapterPath","parserVersion","blockType","pageNumber","pageStart","pageEnd",
            "rowStart","rowEnd","columnStart","columnEnd","fragmentLineStart","fragmentLineEnd","lineStart","lineEnd",
            "textStart","textEnd","offsetUnit","headerRepeated","wholeDocument","overlapChars","manual","originalFileContainsContent",
            "sheetName","tableName","rowNumbers","headerRow","rowNumberUnit","formulaPolicy");
    private static final Set<String> BLOCK_FIELDS=Set.of("blockIndex","lineStart","lineEnd","pageNumber","pageStart","pageEnd","sheetName",
            "rowStart","rowEnd","columnStart","columnEnd","language","slideNumber","bbox","provenance","generated","imageNumber","imageWidth","imageHeight","vlmModel","headerRow","formulaPolicy","rowNumberUnit","tableName","headerPolicy");
    public KnowledgeSearchService(KnowledgeBaseService bases,DocumentService documents,EmbeddingClient embeddings,VectorStore vectors,ObjectMapper json,SearchProperties config) {
        this.bases=bases;this.documents=documents;this.embeddings=embeddings;this.vectors=vectors;this.json=json;this.config=config;
    }
    /** No database transaction spans the model HTTP call. Authorization is repeated in the final filtered vector query. */
    public List<Evidence> search(KnowledgeBasePrincipal actor,Request request,String requestId) {
        return recall(actor,request,requestId,request.topK());
    }
    public List<Evidence> recall(KnowledgeBasePrincipal actor,Request request,String requestId,int limit) {
        String query=request.query()==null?"":request.query().strip();
        if(query.isBlank() || query.codePointCount(0,query.length())>config.maxQueryChars()
                || query.codePoints().anyMatch(c->c==0 || (c>=0xd800 && c<=0xdfff))
                || request.topK()==null || request.topK()<1 || request.topK()>config.maxTopK() || !request.isMinScoreValid())
            throw invalid("Check query, topK and finite minScore limits");
        List<UUID> kbIds=ids(request.kbIds(),config.maxKbIds(),false);
        List<UUID> docIds=ids(request.documentIds(),config.maxDocumentIds(),true);
        // Resolve every requested resource before choosing models or making any external call. No silent scope truncation.
        var selected=new ArrayList<KnowledgeBase>();
        for(UUID id:kbIds)selected.add(bases.requireOwned(actor,id));
        var kbSet=new HashSet<>(kbIds);
        for(UUID id:docIds)if(!kbSet.contains(documents.requireOwned(actor,id).getKbId()))
            throw new KnowledgeBaseException(400,"KB_SEARCH_DOCUMENT_SCOPE","Every document must belong to one of the selected knowledge bases");
        var groups=new LinkedHashMap<EmbeddingClient.Binding,List<KnowledgeBase>>();
        var revisions=new HashSet<String>();
        for(var base:selected) {
            if(base.getEmbeddingModelId()==null)throw new KnowledgeBaseException(409,"KB_MODEL_UNBOUND","Bind the selected knowledge base before searching");
            try {
                var model=json.readTree(base.getEmbeddingConfigJson());
                // Null expectedAiRevision groups empty and populated bases with the same immutable local identity together.
                var binding=new EmbeddingClient.Binding(base.getEmbeddingModelId(),base.getEmbeddingProvider(),model.path("modelName").asText(),base.getEmbeddingDimension(),
                        model.path("embeddingSpace").asText(),model.path("configVersion").asText(),base.getEmbeddingConfigRevision());
                groups.computeIfAbsent(binding,key->new ArrayList<>()).add(base);
                if(base.getEmbeddingAiConfigRevision()!=null)revisions.add(base.getEmbeddingAiConfigRevision());
            } catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("Invalid persisted model binding",e);}
        }
        if(groups.size()!=1 || revisions.size()>1)
            throw new KnowledgeBaseException(409,"KB_SEARCH_MODEL_MISMATCH","Search one embedding model and configuration revision per request; query incompatible knowledge bases separately");
        if(revisions.isEmpty())return List.of(); // No published vectors have pinned an AI revision yet; no paid call is necessary.
        var identity=groups.keySet().iterator().next();String revision=revisions.iterator().next();
        var binding=new EmbeddingClient.Binding(identity.modelId(),identity.provider(),identity.modelName(),identity.dimension(),identity.embeddingSpace(),identity.configVersion(),identity.localRevision(),revision);
        var result=embeddings.embed(binding,List.of(query),requestId,Instant.now().plus(config.deadline()));
        if(result==null || result.vectors()==null || result.vectors().size()!=1 || !revision.equals(result.aiConfigRevision()))
            throw new KnowledgeBaseException(502,"KB_MODEL_INVALID_RESPONSE","Query embedding does not match the bound index");
        var scopes=selected.stream().map(base->new VectorStore.Scope(actor,base.getId(),base.getVectorNamespace(),base.getEmbeddingModelId(),base.getEmbeddingConfigRevision())).toList();
        return vectors.query(scopes,result.vectors().get(0),revision,docIds,limit,request.minScore()).stream().map(hit->{
            double score=1-hit.cosineDistance();
            if(!Double.isFinite(score))throw new KnowledgeBaseException(500,"KB_INDEX_INVALID","Index returned an invalid cosine distance");
            return new Evidence(hit.chunkId(),hit.kbId(),hit.documentId(),hit.documentName(),hit.chunkIndex(),hit.generation(),hit.content(),
                    Math.max(-1,Math.min(1,score)),"COSINE_SIMILARITY",source(hit.metadataJson()),"/api/documents/"+hit.documentId()+"/file");
        }).toList();
    }
    private List<UUID> ids(List<UUID> values,int maximum,boolean optional) {
        if(values==null && optional)return List.of();
        if(values==null || values.isEmpty() || values.size()>maximum || values.stream().anyMatch(Objects::isNull) || new HashSet<>(values).size()!=values.size())
            throw invalid("Resource ids must be nonempty, unique, valid and within configured count limits");
        return values.stream().sorted().toList();
    }
    public Map<String,Object> source(String raw) {
        try {
            var root=json.readTree(raw);if(root==null || !root.isObject())throw new IllegalStateException("Invalid source metadata");
            var output=new LinkedHashMap<String,Object>();
            root.fields().forEachRemaining(field->{if(SOURCE_FIELDS.contains(field.getKey()))output.put(field.getKey(),field.getValue());});
            if(root.path("sourceBlocks").isArray()) {
                var blocks=new ArrayList<Map<String,Object>>();
                for(var block:root.path("sourceBlocks")) {
                    var safe=new LinkedHashMap<String,Object>();
                    block.fields().forEachRemaining(field->{if(BLOCK_FIELDS.contains(field.getKey()))safe.put(field.getKey(),field.getValue());});
                    blocks.add(safe);
                }
                output.put("sourceBlocks",blocks);
            }
            return output;
        } catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("Invalid persisted source metadata",e);}
    }
    private static KnowledgeBaseException invalid(String message){return new KnowledgeBaseException(400,"KB_INVALID_INPUT",message);}
}
