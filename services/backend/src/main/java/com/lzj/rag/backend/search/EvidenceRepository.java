package com.lzj.rag.backend.search;

import com.lzj.rag.backend.knowledgebase.KnowledgeBaseException;
import com.lzj.rag.backend.security.KnowledgeBasePrincipal;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.*;
import static com.lzj.rag.backend.search.KnowledgeSearchTypes.*;

/** Both channels hydrate from the same authoritative, current, enabled relation snapshot. */
@Repository
public class EvidenceRepository {
    private final NamedParameterJdbcTemplate jdbc;private final KnowledgeSearchService sources;private final KeywordProperties config;
    public EvidenceRepository(NamedParameterJdbcTemplate jdbc,KnowledgeSearchService sources,KeywordProperties config){this.jdbc=jdbc;this.sources=sources;this.config=config;}
    public List<String> versionKeys(KnowledgeBasePrincipal actor,Request request) {
        var p=params(actor,request);p.put("target",config.target());p.put("limit",config.maxScopeDocuments()+1);
        var keys=jdbc.queryForList("""
            SELECT s.document_id::text||':'||s.synced_revision::text FROM kb_keyword_sync s
            JOIN kb_document d ON d.id=s.document_id JOIN kb_knowledge_base b ON b.id=d.kb_id
            WHERE s.tenant_id=:tenant AND s.owner_id=:owner AND d.tenant_id=:tenant AND d.owner_id=:owner
            AND b.tenant_id=:tenant AND b.owner_id=:owner AND b.id IN (:kbs) AND (:allDocs OR d.id IN (:docs))
            AND d.enabled AND d.deleted_at IS NULL AND b.deleted_at IS NULL AND d.active_generation IS NOT NULL
            AND s.revision=s.synced_revision AND s.indexed_target=:target ORDER BY d.id LIMIT :limit
            """,p,String.class);
        if(keys.size()>config.maxScopeDocuments())throw new KnowledgeBaseException(400,"KB_KEYWORD_SCOPE_LIMIT","Narrow keyword document scope to the configured document quota");return keys;
    }
    public Map<UUID,Evidence> hydrate(KnowledgeBasePrincipal actor,Request request,Collection<UUID> ids) {
        if(ids.isEmpty())return Map.of();var p=params(actor,request);p.put("ids",ids);
        var result=new LinkedHashMap<UUID,Evidence>();
        jdbc.query("""
            SELECT c.*,d.name AS document_name FROM kb_chunk c JOIN kb_document d ON d.id=c.document_id
            JOIN kb_knowledge_base b ON b.id=d.kb_id JOIN kb_generation g ON g.document_id=d.id AND g.generation=c.generation
            WHERE c.id IN (:ids) AND b.id IN (:kbs) AND (:allDocs OR d.id IN (:docs))
            AND c.tenant_id=:tenant AND c.owner_id=:owner AND d.tenant_id=:tenant AND d.owner_id=:owner
            AND b.tenant_id=:tenant AND b.owner_id=:owner AND c.kb_id=d.kb_id AND c.generation=d.active_generation
            AND c.enabled AND d.enabled AND d.deleted_at IS NULL AND b.deleted_at IS NULL
            AND c.embedding_config_revision=b.embedding_config_revision AND c.ai_config_revision=b.embedding_ai_config_revision
            AND g.embedding_config_revision=b.embedding_config_revision AND g.ai_config_revision=b.embedding_ai_config_revision
            """,p,(rs,n)->{UUID id=rs.getObject("id",UUID.class),doc=rs.getObject("document_id",UUID.class);result.put(id,new Evidence(id,rs.getObject("kb_id",UUID.class),doc,rs.getString("document_name"),rs.getInt("chunk_index"),rs.getLong("generation"),rs.getString("content"),0,"UNSCORED",sources.source(rs.getString("metadata_json")),"/api/documents/"+doc+"/file"));return id;});
        return result;
    }
    public Map<UUID,Long> validKeywordRevisions(KnowledgeBasePrincipal actor,Request request,Collection<UUID> ids) {
        if(ids.isEmpty())return Map.of();var p=params(actor,request);p.put("ids",ids);p.put("target",config.target());var result=new HashMap<UUID,Long>();
        jdbc.query("SELECT c.id,s.synced_revision FROM kb_chunk c JOIN kb_keyword_sync s ON s.document_id=c.document_id WHERE c.id IN (:ids) AND s.tenant_id=:tenant AND s.owner_id=:owner AND s.revision=s.synced_revision AND s.indexed_target=:target",p,(rs,n)->{result.put(rs.getObject(1,UUID.class),rs.getLong(2));return 0;});return result;
    }
    private Map<String,Object> params(KnowledgeBasePrincipal actor,Request request){var docs=request.documentIds()==null?List.<UUID>of():request.documentIds();return new HashMap<>(Map.of("tenant",actor.tenantId(),"owner",actor.userId(),"kbs",request.kbIds(),"docs",docs.isEmpty()?List.of(new UUID(0,0)):docs,"allDocs",docs.isEmpty()));}
}
