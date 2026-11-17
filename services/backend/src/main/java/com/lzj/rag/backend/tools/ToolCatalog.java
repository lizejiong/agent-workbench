package com.lzj.rag.backend.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lzj.rag.backend.knowledgebase.exception.KnowledgeBaseException;
import com.lzj.rag.backend.security.model.KnowledgeBasePrincipal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import java.net.URI;
import java.util.*;

@Service
public class ToolCatalog {
    final JdbcTemplate db;final ObjectMapper json;final ToolProperties config;final AgentBridge agent;
    final TransactionTemplate tx;
    public ToolCatalog(JdbcTemplate db,ObjectMapper json,ToolProperties config,AgentBridge agent,PlatformTransactionManager transactions){
        this.db=db;this.json=json;this.config=config;this.agent=agent;this.tx=new TransactionTemplate(transactions);tx.setTimeout(10);
    }
    public List<Map<String,Object>> servers(KnowledgeBasePrincipal actor){
        return db.queryForList("SELECT id,name,url,credential_ref,config_version,enabled,updated_at FROM kb_tool_server WHERE tenant_id=? ORDER BY name,id",actor.tenantId());
    }
    public Map<String,Object> server(String tenant,UUID id){
        var rows=db.queryForList("SELECT * FROM kb_tool_server WHERE id=? AND tenant_id=?",id,tenant);
        if(rows.isEmpty())throw denied();return rows.get(0);
    }
    @Transactional(timeout=10)
    public Map<String,Object> save(KnowledgeBasePrincipal actor,UUID id,ToolContracts.Server input){
        URI uri;
        try{uri=URI.create(input.url());}catch(Exception e){throw invalid();}
        String origin=uri.getScheme()+"://"+uri.getRawAuthority();
        if(!config.allowedOrigins().contains(origin) || uri.getHost()==null || uri.getUserInfo()!=null
            || uri.getRawQuery()!=null || uri.getFragment()!=null || !List.of("http","https").contains(uri.getScheme()))throw invalid();
        if(id==null){
            id=UUID.randomUUID();
            db.update("INSERT INTO kb_tool_server(id,tenant_id,name,url,credential_ref,enabled) VALUES(?,?,?,?,?,?)",
                id,actor.tenantId(),input.name(),input.url(),input.credentialRef(),input.enabled());
        }else{
            server(actor.tenantId(),id);
            int count=db.update("UPDATE kb_tool_server SET name=?,url=?,credential_ref=?,enabled=?,config_version=config_version+1,updated_at=clock_timestamp() WHERE id=? AND tenant_id=? AND config_version=?",
                input.name(),input.url(),input.credentialRef(),input.enabled(),id,actor.tenantId(),input.expectedVersion());
            if(count!=1)throw conflict();
            db.update("UPDATE kb_tool_definition SET enabled=false,changed=true WHERE server_id=?",id);
        }
        audit(actor,"SERVER_CONFIG",id.toString());return server(actor.tenantId(),id);
    }
    public Map<String,Object> connection(Map<String,Object> server){
        var out=new LinkedHashMap<String,Object>();
        out.put("serverId",server.get("id"));out.put("url",server.get("url"));
        out.put("credentialRef",server.get("credential_ref"));out.put("configVersion",server.get("config_version"));
        return out;
    }
    public List<Map<String,Object>> discover(KnowledgeBasePrincipal actor,UUID id,boolean save){
        var s=server(actor.tenantId(),id);
        // 网络调用在事务之外；落库时再次核对配置修订，避免发现与配置更新错配。
        Object raw=agent.discover(connection(s));
        List<Map<String,Object>> found;
        try{found=json.convertValue(raw,new TypeReference<>(){});}catch(Exception e){throw invalid();}
        if(found.size()>128)throw invalid();
        if(!save)return found;
        tx.executeWithoutResult(status->{
            var current=db.queryForMap("SELECT * FROM kb_tool_server WHERE id=? AND tenant_id=? FOR UPDATE",id,actor.tenantId());
            if(!Objects.equals(current.get("config_version"),s.get("config_version")))throw conflict();
            var names=new HashSet<String>();
            for(var row:found){
                String key=(String)row.get("toolKey"), name=(String)row.get("remoteToolName"), hash=(String)row.get("schemaHash");
                if(key==null || key.length()>200 || name==null || name.length()>120 || !names.add(name)
                    || !key.equals("mcp:"+id+":"+name) || hash==null || !hash.matches("[a-f0-9]{64}"))throw invalid();
                db.update("INSERT INTO kb_tool_definition(tool_key,server_id,model_name,remote_name,discovered,observed_hash) VALUES(?,?,?,?,?::jsonb,?) "
                    +"ON CONFLICT(tool_key) DO UPDATE SET discovered=excluded.discovered,observed_hash=excluded.observed_hash,changed=(kb_tool_definition.schema_hash IS DISTINCT FROM excluded.observed_hash),updated_at=clock_timestamp()",
                    key,id,row.get("modelName"),name,encode(row),hash);
            }
            var old=db.queryForList("SELECT tool_key,remote_name FROM kb_tool_definition WHERE server_id=?",id);
            for(var row:old)if(!names.contains(row.get("remote_name")))db.update("UPDATE kb_tool_definition SET enabled=false,changed=true WHERE tool_key=?",row.get("tool_key"));
            audit(actor,"DISCOVER",id.toString());
        });
        return definitions(actor,id);
    }
    public List<Map<String,Object>> definitions(KnowledgeBasePrincipal actor,UUID id){
        server(actor.tenantId(),id);
        var rows=db.queryForList("SELECT tool_key,model_name,remote_name,discovered,published,observed_hash,schema_hash,enabled,approved_read_only,changed FROM kb_tool_definition WHERE server_id=? ORDER BY remote_name",id);
        for(var row:rows){row.put("discovered",decode(row.get("discovered")));if(row.get("published")!=null)row.put("published",decode(row.get("published")));}
        return rows;
    }
    @Transactional(timeout=10)
    public Map<String,Object> publish(KnowledgeBasePrincipal actor,String key,ToolContracts.Publish input){
        var rows=db.queryForList("SELECT t.*,s.tenant_id,s.config_version AS server_version FROM kb_tool_definition t JOIN kb_tool_server s ON s.id=t.server_id WHERE t.tool_key=? AND s.tenant_id=? FOR UPDATE OF t",key,actor.tenantId());
        if(rows.isEmpty())throw denied();var row=rows.get(0);
        if(!Objects.equals(input.schemaHash(),row.get("observed_hash")))throw conflict();
        if(input.enabled() && !input.readOnly())throw invalid();
        var definition=decode(row.get("discovered"));definition.remove("readOnlyHint");
        definition.put("readOnly",true);definition.put("timeoutMs",input.timeoutMs());definition.put("maxResultBytes",input.maxResultBytes());
        definition.put("configVersion",row.get("server_version"));
        db.update("UPDATE kb_tool_definition SET published=?::jsonb,schema_hash=?,config_version=?,enabled=?,approved_read_only=?,changed=false,updated_at=clock_timestamp() WHERE tool_key=?",
            encode(definition),input.schemaHash(),row.get("server_version"),input.enabled(),input.readOnly(),key);
        audit(actor,input.enabled()?"TOOL_ENABLE":"TOOL_DISABLE",key);return definition;
    }
    @Transactional(timeout=10)
    public void grant(KnowledgeBasePrincipal actor,String key,ToolContracts.Grant grant){
        var count=db.queryForObject("SELECT count(*) FROM kb_tool_definition t JOIN kb_tool_server s ON s.id=t.server_id WHERE t.tool_key=? AND s.tenant_id=?",Long.class,key,actor.tenantId());
        if(count==null || count!=1)throw denied();
        db.update("INSERT INTO kb_tool_grant(tenant_id,user_id,tool_key,enabled) VALUES(?,?,?,?) ON CONFLICT(tenant_id,user_id,tool_key) DO UPDATE SET enabled=excluded.enabled,updated_at=clock_timestamp()",
            actor.tenantId(),grant.userId(),key,grant.enabled());audit(actor,grant.enabled()?"GRANT":"REVOKE",key);
    }
    public List<Map<String,Object>> allowed(KnowledgeBasePrincipal actor){
        return db.queryForList("SELECT t.published FROM kb_tool_definition t JOIN kb_tool_server s ON s.id=t.server_id JOIN kb_tool_grant g ON g.tool_key=t.tool_key "
            +"WHERE s.tenant_id=? AND g.tenant_id=? AND g.user_id=? AND g.enabled AND s.enabled AND t.enabled AND t.approved_read_only AND NOT t.changed AND t.schema_hash=t.observed_hash AND t.config_version=s.config_version ORDER BY t.tool_key",
            actor.tenantId(),actor.tenantId(),actor.userId()).stream().map(r->decode(r.get("published"))).toList();
    }
    public Map<String,Object> approved(KnowledgeBasePrincipal actor,String key){
        return allowed(actor).stream().filter(d->key.equals(d.get("toolKey"))).findFirst().orElseThrow(this::denied);
    }
    public void audit(KnowledgeBasePrincipal actor,String action,String resource){
        db.update("INSERT INTO kb_tool_config_audit(id,tenant_id,actor_id,action,resource) VALUES(?,?,?,?,?)",UUID.randomUUID(),actor.tenantId(),actor.userId(),action,resource);
    }
    public String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw invalid();}}
    public Map<String,Object> decode(Object value){try{return json.readValue(value.toString(),new TypeReference<>(){});}catch(Exception e){throw invalid();}}
    KnowledgeBaseException denied(){return new KnowledgeBaseException(404,"TOOL_NOT_ACCESSIBLE","Tool or configuration not accessible");}
    KnowledgeBaseException invalid(){return new KnowledgeBaseException(400,"TOOL_CONFIG_INVALID","Invalid tool configuration or deployment access policy");}
    KnowledgeBaseException conflict(){return new KnowledgeBaseException(409,"TOOL_CONFIG_CHANGED","Tool configuration changed; discover and explicitly approve the current schema");}
}
