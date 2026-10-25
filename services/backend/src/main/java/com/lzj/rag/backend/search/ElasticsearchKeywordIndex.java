package com.lzj.rag.backend.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lzj.rag.backend.knowledgebase.KnowledgeBaseException;
import jakarta.annotation.PreDestroy;
import org.apache.http.HttpHost;
import org.apache.http.message.BasicHeader;
import org.elasticsearch.client.RestClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import java.io.StringReader;
import java.time.Instant;
import java.util.*;

@Component @EnableConfigurationProperties({KeywordProperties.class,HybridProperties.class})
public class ElasticsearchKeywordIndex implements KeywordIndex {
    private final KeywordProperties config;private final ObjectMapper json;private final ElasticsearchClient client;
    private final RestClientTransport transport;private volatile boolean checked;
    public ElasticsearchKeywordIndex(KeywordProperties config,ObjectMapper json) {
        this.config=config;this.json=json;
        if(!config.enabled()){client=null;transport=null;return;}
        int timeout=Math.toIntExact(config.callTimeout().toMillis());
        var builder=RestClient.builder(HttpHost.create(config.endpoint())).setRequestConfigCallback(b->b.setConnectTimeout(timeout).setConnectionRequestTimeout(timeout).setSocketTimeout(timeout))
                .setHttpClientConfigCallback(b->b.setMaxConnTotal(8).setMaxConnPerRoute(8).disableAuthCaching());
        if(config.apiKey()!=null && !config.apiKey().isBlank())builder.setDefaultHeaders(new BasicHeader[]{new BasicHeader("Authorization","ApiKey "+config.apiKey())});
        transport=new RestClientTransport(builder.build(),new JacksonJsonpMapper(json));client=new ElasticsearchClient(transport);
    }
    @Override public synchronized void ready() {
        if(checked)return;if(client==null)throw unavailable();
        try {
            var version=client.info().version().number();String[] parts=version.split("\\.");
            if(parts.length<2 || !parts[0].equals("8") || Integer.parseInt(parts[1])<18)throw new KnowledgeBaseException(503,"KB_KEYWORD_VERSION","Use the tested Elasticsearch 8.18+ protocol family; server validation required");
            var tokens=client.indices().analyze(a->a.analyzer(config.analyzer()).text("中华人民共和国知识库 ZX1472")).tokens();
            if(tokens.isEmpty() || tokens.stream().noneMatch(t->t.token().codePointCount(0,t.token().length())>1 && t.token().codePoints().anyMatch(c->c>127)))
                throw new KnowledgeBaseException(503,"KB_KEYWORD_ANALYZER","Configured Chinese analyzer did not produce supported tokens");
            if(!client.indices().exists(e->e.index(config.index())).value()) {
                var fields=new LinkedHashMap<String,Object>();
                for(String name:List.of("id","chunkId","kbId","documentId","tenantId","ownerId","versionKey","contentHash"))fields.put(name,Map.of("type","keyword"));
                for(String name:List.of("generation","syncRevision","chunkIndex"))fields.put(name,Map.of("type","long"));
                for(String name:List.of("enabled","chunkEnabled","documentEnabled"))fields.put(name,Map.of("type","boolean"));fields.put("documentName",Map.of("type","keyword","index",false));
                fields.put("content",Map.of("type","text","analyzer",config.analyzer(),"similarity","BM25"));fields.put("source",Map.of("type","object","enabled",false));
                String mapping=encode(Map.of("settings",Map.of("number_of_shards",1,"number_of_replicas",0),"mappings",Map.of("dynamic","strict","_meta",Map.of("kb_schema","1","kb_analyzer",config.analyzer()),"properties",fields)));
                try{client.indices().create(c->c.index(config.index()).withJson(new StringReader(mapping)));}
                catch(co.elastic.clients.elasticsearch._types.ElasticsearchException race){if(!client.indices().exists(e->e.index(config.index())).value())throw race;}
            }
            var mapping=client.indices().getMapping(g->g.index(config.index())).result().get(config.index()).mappings();
            if(!"1".equals(mapping.meta().getOrDefault("kb_schema",co.elastic.clients.json.JsonData.of("")).to(String.class))
                    || !config.analyzer().equals(mapping.properties().get("content").text().analyzer())
                    || !"BM25".equals(mapping.properties().get("content").text().similarity()))throw new KnowledgeBaseException(503,"KB_KEYWORD_MAPPING","Create a new dedicated KB index with the expected schema/analyzer; do not reuse an unrelated index");
            checked=true;
        }catch(KnowledgeBaseException e){throw e;}catch(Exception e){throw unavailable();}
    }
    @Override public void write(List<IndexedChunk> chunks,Instant deadline) {
        ready();try {
            for(int start=0;start<chunks.size();start+=config.batchSize()) {
                deadline(deadline);var builder=new BulkRequest.Builder().index(config.index()).refresh(Refresh.WaitFor);
                for(var row:chunks.subList(start,Math.min(chunks.size(),start+config.batchSize())))builder.operations(o->o.index(i->i.id(row.id()).document(row)));
                if(client.bulk(builder.build()).errors())throw unavailable();
            }
        }catch(KnowledgeBaseException e){throw e;}catch(Exception e){throw unavailable();}
    }
    @Override public List<Hit> query(String query,String tenant,String owner,List<String> keys,int limit,Instant deadline) {
        if(keys.isEmpty())return List.of();deadline(deadline);ready();deadline(deadline);
        try {
            var result=client.search(s->s.index(config.index()).size(limit).timeout(config.callTimeout().toMillis()+"ms")
                .source(source->source.filter(f->f.includes("chunkId","syncRevision","contentHash")))
                .query(q->q.bool(b->b.filter(f->f.term(t->t.field("tenantId").value(tenant)))
                    .filter(f->f.term(t->t.field("ownerId").value(owner)))
                    .filter(f->f.term(t->t.field("enabled").value(true)))
                    .filter(f->f.terms(t->t.field("versionKey").terms(v->v.value(keys.stream().map(FieldValue::of).toList()))))
                    .must(m->m.match(t->t.field("content").query(query)))))
                .sort(v->v.score(sc->sc.order(SortOrder.Desc))).sort(v->v.field(f->f.field("id").order(SortOrder.Asc)))
                .trackTotalHits(t->t.enabled(false)),Map.class);
            if(result.timedOut() || result.shards().failed().longValue()>0)throw unavailable();
            var hits=new ArrayList<Hit>();int rank=0;
            for(var hit:result.hits().hits()) {
                rank++;var source=hit.source();if(source==null || hit.score()==null || !Double.isFinite(hit.score()))throw unavailable();
                hits.add(new Hit(UUID.fromString(source.get("chunkId").toString()),((Number)source.get("syncRevision")).longValue(),source.get("contentHash").toString(),rank,hit.score()));
            }
            return List.copyOf(hits);
        }catch(KnowledgeBaseException e){throw e;}catch(Exception e){var failure=unavailable();failure.initCause(e);throw failure;}
    }
    @Override public void deleteBefore(UUID document,long revision,Instant deadline) {
        deadline(deadline);ready();deadline(deadline);
        try {
            var result=client.deleteByQuery(d->d.index(config.index()).refresh(true).waitForCompletion(true).conflicts(co.elastic.clients.elasticsearch._types.Conflicts.Proceed)
                .query(q->q.bool(b->b.filter(f->f.term(t->t.field("documentId").value(document.toString())))
                    .filter(f->f.range(r->r.untyped(u->u.field("syncRevision").lt(co.elastic.clients.json.JsonData.of(revision))))))));
            if(result.timedOut() || !result.failures().isEmpty() || result.versionConflicts()!=0)throw unavailable();
        }catch(KnowledgeBaseException e){throw e;}catch(Exception e){throw unavailable();}
    }
    @PreDestroy void close()throws Exception{if(transport!=null)transport.close();}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
    private static void deadline(Instant deadline){if(Thread.currentThread().isInterrupted() || !Instant.now().isBefore(deadline))throw new KnowledgeBaseException(504,"KB_KEYWORD_TIMEOUT","Keyword channel time budget expired");}
    private static KnowledgeBaseException unavailable(){return new KnowledgeBaseException(503,"KB_KEYWORD_UNAVAILABLE","Keyword service or schema is unavailable; inspect sync status, no source/credential details are exposed");}
}
