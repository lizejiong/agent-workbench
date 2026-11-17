package com.lzj.rag.backend.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lzj.rag.backend.knowledgebase.exception.KnowledgeBaseException;
import okhttp3.*;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Python 控制端仅对本服务开放；浏览器不持有服务 token。 */
@Component
public class AgentBridge {
    final ToolProperties config;final ObjectMapper json;final OkHttpClient client;
    public AgentBridge(ToolProperties config,ObjectMapper json){
        this.config=config;this.json=json;
        this.client=new OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .connectTimeout(3,TimeUnit.SECONDS).readTimeout(20,TimeUnit.SECONDS).callTimeout(25,TimeUnit.SECONDS).retryOnConnectionFailure(false).build();
    }
    public Object discover(Map<String,Object> server){
        if(config.agentToken().length()<32)throw error("AGENT_PROXY_UNCONFIGURED");
        try {
            var request=new Request.Builder().url(config.agentOrigin()+"/internal/mcp/discover")
                .header("Authorization","Bearer "+config.agentToken()).header("X-Agent-Audience","mcp-control")
                .post(RequestBody.create(json.writeValueAsBytes(server),MediaType.get("application/json"))).build();
            try(var response=client.newCall(request).execute()){
                if(!response.isSuccessful() || response.body()==null)throw error("MCP_DISCOVERY_UNAVAILABLE");
                var stream=response.body().byteStream();byte[] bytes=stream.readNBytes(524289);
                if(bytes.length>524288)throw error("MCP_DISCOVERY_BUDGET");
                return json.readValue(bytes,Object.class);
            }
        }catch(KnowledgeBaseException e){throw e;}catch(Exception e){throw error("MCP_DISCOVERY_UNAVAILABLE");}
    }
    private KnowledgeBaseException error(String code){return new KnowledgeBaseException(503,code,"Agent control unavailable");}
}
