package com.lzj.rag.backend.tools;

import com.fasterxml.jackson.databind.*;
import com.lzj.rag.backend.model.catalog.EmbeddingCatalog;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** 真实 PostgreSQL/Flyway/安全链/短事务。远端发现响应是明确控制桩，HTTP MCP 另由 Python fixture 验证。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named="KB_TEST_JDBC_URL",matches=".+")
class ToolIntegrationTest {
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired JdbcTemplate db;@Autowired ToolLedger ledger;
    @MockitoBean AgentBridge bridge;
    final AtomicReference<String> observed=new AtomicReference<>("a".repeat(64));
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",()->System.getenv("KB_TEST_JDBC_URL"));
        r.add("spring.datasource.username",()->System.getenv("KB_TEST_DB_USERNAME"));
        r.add("spring.datasource.password",()->Objects.toString(System.getenv("KB_TEST_DB_PASSWORD"),""));
        r.add("kb.rag.internal.enabled",()->true);
        r.add("kb.rag.internal.service-token-hashes.agent-test",()->EmbeddingCatalog.sha256("s".repeat(32)));
        r.add("kb.tools.allowed-origins",()->"http://127.0.0.1:8765");
        var users=List.of("alice","bob","carol","operator");
        for(int i=0;i<users.size();i++){
            String user=users.get(i),prefix="kb.security.accounts["+i+"].";
            r.add(prefix+"username",()->user);r.add(prefix+"password",()->user+"-local-test-password");
            r.add(prefix+"tenant-id",()->user.equals("carol")?"tenant-b":"tenant-a");
            r.add(prefix+"user-id",()->"user-"+user);r.add(prefix+"operator",()->user.equals("operator"));
        }
    }
    @BeforeEach void clean(){
        for(String table:List.of("kb_tool_dependency","kb_tool_call","kb_tool_grant","kb_tool_definition","kb_tool_server","kb_tool_config_audit","kb_agent_message","kb_agent_context_summary","kb_agent_run","kb_agent_conversation","kb_rag_delegation"))db.update("DELETE FROM "+table);
        observed.set("a".repeat(64));
        when(bridge.discover(anyMap())).thenAnswer(invocation->{
            Map<String,Object> s=invocation.getArgument(0);String id=s.get("serverId").toString();
            var d=new LinkedHashMap<String,Object>();
            d.put("toolKey","mcp:"+id+":lookup");d.put("modelName","mcp_"+id.replace("-","")+"_lookup");
            d.put("displayName","只读查询");d.put("description","受控测试查询");
            d.put("inputSchema",Map.of("type","object","properties",Map.of("query",Map.of("type","string")),"required",List.of("query"),"additionalProperties",false));
            d.put("outputSchema",null);d.put("kind","mcp");d.put("readOnly",true);d.put("readOnlyHint",false);
            d.put("configVersion",s.get("configVersion"));d.put("timeoutMs",10000);d.put("maxResultBytes",4096);
            d.put("serverId",id);d.put("remoteToolName","lookup");d.put("schemaHash",observed.get());return List.of(d);
        });
    }
    record Run(String conversation,String id,String delegation){}
    Run run(String user)throws Exception{
        var c=user(post("/api/conversations").content(body(Map.of("requestId",UUID.randomUUID(),"kbIds",List.of()))),user,201);
        String cid=c.path("id").asText();
        String delegation=user(post("/api/conversations/"+cid+"/delegations").content(body(Map.of("serviceId","agent-test"))),user,201).path("delegationToken").asText();
        var input=Map.of("requestId",UUID.randomUUID(),"messageId",UUID.randomUUID(),"question","查询测试","deadline",Instant.now().plusSeconds(60).toString(),"mode","agent");
        var r=internal(post("/internal/agent/conversations/"+cid+"/runs").content(body(input)),delegation,200);
        return new Run(cid,r.path("runId").asText(),delegation);
    }
    JsonNode server()throws Exception{return user(post("/api/tools/servers").content(body(Map.of("name","Fixture","url","http://127.0.0.1:8765/mcp","credentialRef","env:ATLAS_TEST_TOKEN","enabled",true))),"operator",200);}
    JsonNode discover(String id)throws Exception{return user(post("/api/tools/servers/"+id+"/discover"),"operator",200);}
    String key(String id){return "mcp:"+id+":lookup";}
    void publish(String key,boolean enabled)throws Exception{user(put("/api/tools/publish").param("toolKey",key).content(body(Map.of("schemaHash",observed.get(),"enabled",enabled,"readOnly",true,"timeoutMs",10000,"maxResultBytes",4096))),"operator",200);}
    void grant(String key,boolean enabled)throws Exception{user(put("/api/tools/grant").param("toolKey",key).content(body(Map.of("userId","user-alice","enabled",enabled))),"operator",200);}
    String approved()throws Exception{String id=server().path("id").asText();discover(id);publish(key(id),true);grant(key(id),true);return key(id);}
    Map<String,Object> start(String key,String callId){return Map.of("callId",callId,"providerCallId","vendor-"+callId,"toolKey",key,"configVersion",1,"schemaHash",observed.get(),"batch",1,"order",0,"arguments",Map.of("query","状态","password","do-not-persist-this"));}
    JsonNode start(Run r,String key,String id)throws Exception{return internal(post("/internal/tools/runs/"+r.id+"/calls").content(body(start(key,id))),r.delegation,200);}
    void finish(Run r,String id)throws Exception{
        var content=Map.of("sourceId","M"+id.substring(1),"content",Map.of("window","09:00–18:00","password","sensitive-remote-value"));
        var result=new LinkedHashMap<String,Object>();result.put("callId",id);result.put("status","success");result.put("modelContent",content);result.put("summary","已返回测试结果");
        result.put("evidenceRefs",List.of("M"+id.substring(1)));result.put("sourceType","mcp");result.put("errorCode",null);result.put("truncated",false);result.put("durationMs",10);
        internal(put("/internal/tools/runs/"+r.id+"/calls/"+id).content(body(result)),r.delegation,200);
    }
    @Test void newToolsAreClosedAndRequireExplicitReadOnlyPublicationAndUserGrant()throws Exception{
        user(get("/api/tools/servers"),"alice",403);
        var r=run("alice");String id=server().path("id").asText();var found=discover(id);
        assertThat(found.get(0).path("enabled").asBoolean()).isFalse();
        assertThat(internal(get("/internal/tools/runs/"+r.id+"/tools"),r.delegation,200)).hasSize(2);
        user(put("/api/tools/publish").param("toolKey",key(id)).content(body(Map.of("schemaHash",observed.get(),"enabled",true,"readOnly",false,"timeoutMs",10000,"maxResultBytes",4096))),"operator",400);
        String denied="T"+UUID.randomUUID().toString().replace("-","");
        assertThat(start(r,key(id),denied).path("execute").asBoolean()).isFalse();
        publish(key(id),true);
        assertThat(internal(get("/internal/tools/runs/"+r.id+"/tools"),r.delegation,200)).hasSize(2);
        grant(key(id),true);
        assertThat(internal(get("/internal/tools/runs/"+r.id+"/tools"),r.delegation,200)).hasSize(3);
        assertThat(db.queryForObject("SELECT status FROM kb_tool_call WHERE call_id=?",String.class,denied)).isEqualTo("denied");
    }
    @Test void callsAreBoundToRunServiceAndOwnerAndCannotBeReplayed()throws Exception{
        String key=approved();var alice=run("alice");var bob=run("bob");String callId="T"+UUID.randomUUID().toString().replace("-","");
        assertThat(start(alice,key,callId).path("execute").asBoolean()).isTrue();
        internal(get("/internal/tools/runs/"+alice.id+"/calls/"+callId+"/connection"),bob.delegation,404);
        mvc.perform(get("/internal/tools/runs/"+alice.id+"/tools").header("Authorization","Bearer "+"s".repeat(32)).header("X-Rag-Service","agent-test").header("X-Rag-Delegation",alice.delegation)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        internal(post("/internal/tools/runs/"+alice.id+"/calls").content(body(start(key,callId))),alice.delegation,409);
        finish(alice,callId);
        assertThat(start(alice,key,callId).path("execute").asBoolean()).isFalse();
        assertThat(db.queryForObject("SELECT count(*) FROM kb_tool_call WHERE call_id=?",Integer.class,callId)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT arguments::text||result::text FROM kb_tool_call WHERE call_id=?",String.class,callId)).doesNotContain("do-not-persist-this","sensitive-remote-value");
        var records=user(get("/api/tools/calls").param("runId",alice.id),"operator",200);
        assertThat(records.get(0).has("arguments")).isFalse();
    }
    @Test void revokeBetweenStartAndExecutionDeniesConnectionAndAuthorization()throws Exception{
        String key=approved();var r=run("alice");String id="T"+UUID.randomUUID().toString().replace("-","");start(r,key,id);
        grant(key,false);
        internal(get("/internal/tools/runs/"+r.id+"/calls/"+id+"/connection"),r.delegation,404);
        internal(post("/internal/tools/runs/"+r.id+"/calls/"+id+"/authorize"),r.delegation,404);
    }
    @Test void changedSchemaCannotSilentlyExpandPublishedCapabilities()throws Exception{
        String key=approved();String serverId=key.split(":")[1];var r=run("alice");
        observed.set("b".repeat(64));var changed=discover(serverId);
        assertThat(changed.get(0).path("changed").asBoolean()).isTrue();
        assertThat(internal(get("/internal/tools/runs/"+r.id+"/tools"),r.delegation,200)).hasSize(2);
        user(put("/api/tools/publish").param("toolKey",key).content(body(Map.of("schemaHash","a".repeat(64),"enabled",true,"readOnly",true,"timeoutMs",10000,"maxResultBytes",4096))),"operator",409);
    }
    @Test void historyReplayHidesToolParametersAndDependentAnswerAfterRevocation()throws Exception{
        String key=approved();var r=run("alice");String callId="T"+UUID.randomUUID().toString().replace("-","");start(r,key,callId);finish(r,callId);
        String modelName=db.queryForObject("SELECT model_name FROM kb_tool_definition WHERE tool_key=?",String.class,key);
        String answer="服务窗口为 09:00–18:00。[M"+callId.substring(1)+"]";
        var block=new LinkedHashMap<String,Object>();block.put("blockId","B"+UUID.randomUUID().toString().replace("-",""));block.put("kind","tool");block.put("name",modelName);block.put("toolCallId",callId);block.put("status","done");
        block.put("startedAt",Instant.now().toString());block.put("durationMs",10);block.put("batchId","B"+UUID.randomUUID().toString().replace("-",""));block.put("callIndex",0);block.put("arguments",Map.of("query","状态"));block.put("evidenceIds",List.of());
        var checkpoint=new LinkedHashMap<String,Object>();checkpoint.put("sequence",1);checkpoint.put("status","COMPLETED");checkpoint.put("content",answer);checkpoint.put("evidence",List.of());checkpoint.put("citations",List.of());
        checkpoint.put("tools",List.of(Map.of("toolCallId",callId,"name",modelName,"status","success")));checkpoint.put("eventSeq",4);
        checkpoint.put("blocks",List.of(block,Map.of("blockId","B"+UUID.randomUUID().toString().replace("-",""),"kind","answer","status","done","text",answer)));
        internal(patch("/internal/agent/conversations/"+r.conversation+"/runs/"+r.id).content(body(checkpoint)),r.delegation,200);
        var before=user(get("/api/conversations/"+r.conversation+"/messages"),"alice",200).path("records").get(1);
        assertThat(before.path("blocks").get(0).path("sourceType").asText()).isEqualTo("mcp");
        assertThat(before.path("content").asText()).isEqualTo(answer);
        grant(key,false);
        var after=user(get("/api/conversations/"+r.conversation+"/messages"),"alice",200).path("records").get(1);
        assertThat(after.path("sourcesRestricted").asBoolean()).isTrue();assertThat(after.path("blocks")).isEmpty();assertThat(after.path("tools")).isEmpty();
        assertThat(after.path("content").asText()).doesNotContain("09:00");
    }
    @Test void expiredCallsAreDurablyClosedAndNoSecretsAreStoredInServerConfig()throws Exception{
        String key=approved();var r=run("alice");String id="T"+UUID.randomUUID().toString().replace("-","");start(r,key,id);
        db.update("UPDATE kb_tool_call SET deadline_at=clock_timestamp()-interval '1 second' WHERE call_id=?",id);ledger.expire();
        assertThat(db.queryForObject("SELECT status FROM kb_tool_call WHERE call_id=?",String.class,id)).isEqualTo("timeout");
        assertThat(db.queryForObject("SELECT credential_ref FROM kb_tool_server",String.class)).isEqualTo("env:ATLAS_TEST_TOKEN");
        assertThat(db.queryForObject("SELECT count(*) FROM kb_tool_config_audit",Integer.class)).isGreaterThanOrEqualTo(4);
    }
    JsonNode user(MockHttpServletRequestBuilder req,String user,int expected)throws Exception{return request(req.with(httpBasic(user,user+"-local-test-password")),expected);}
    JsonNode internal(MockHttpServletRequestBuilder req,String delegation,int expected)throws Exception{return request(req.header("Authorization","Bearer "+"s".repeat(32)).header("X-Rag-Service","agent-test").header("X-Rag-Audience","backend-tools").header("X-Rag-Delegation",delegation).header("X-Rag-Deadline",Instant.now().plusSeconds(10).toString()),expected);}
    JsonNode request(MockHttpServletRequestBuilder req,int expected)throws Exception{
        var response=mvc.perform(req.contentType(APPLICATION_JSON)).andReturn().getResponse();assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(expected);
        if(response.getContentAsByteArray().length==0)return json.createObjectNode();var envelope=json.readTree(response.getContentAsByteArray());return envelope.has("data")?envelope.path("data"):envelope;
    }
    String body(Object value)throws Exception{return json.writeValueAsString(value);}
}
