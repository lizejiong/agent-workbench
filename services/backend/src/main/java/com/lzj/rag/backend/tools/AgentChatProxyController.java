package com.lzj.rag.backend.tools;

import com.lzj.rag.backend.knowledgebase.exception.KnowledgeBaseException;
import jakarta.servlet.http.HttpServletRequest;
import okhttp3.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** 浏览器只访问业务后端。关闭下游连接时关闭 Python 上游，禁止代理重试整轮执行。 */
@RestController
public class AgentChatProxyController {
    final ToolProperties config;final OkHttpClient http;
    public AgentChatProxyController(ToolProperties config){
        this.config=config;http=new OkHttpClient.Builder().connectTimeout(3,TimeUnit.SECONDS)
            .readTimeout(135,TimeUnit.SECONDS).callTimeout(140,TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build();
    }
    @PostMapping({"/api/agent/chat","/api/agent/chat/stream"})
    public ResponseEntity<StreamingResponseBody> chat(@org.springframework.web.bind.annotation.RequestBody byte[] body,HttpServletRequest incoming){
        if(config.agentToken().length()<32)throw new KnowledgeBaseException(503,"AGENT_PROXY_UNCONFIGURED","Agent proxy requires service credentials");
        if(body.length>20000)throw new KnowledgeBaseException(413,"AGENT_REQUEST_BUDGET","Agent request too large");
        var builder=new Request.Builder().url(config.agentOrigin()+incoming.getRequestURI())
            .header("X-Agent-Service-Token",config.agentToken()).header("X-Agent-Audience","agent-execution")
            .post(okhttp3.RequestBody.create(body,MediaType.get("application/json")));
        for(String name:List.of("Authorization","X-Request-Id","traceparent","X-Agent-Stream-Version")){
            var value=incoming.getHeader(name);if(value!=null)builder.header(name,value);
        }
        var call=http.newCall(builder.build());Response response;
        try{response=call.execute();}catch(Exception e){call.cancel();throw new KnowledgeBaseException(503,"AGENT_UNAVAILABLE","Agent unavailable");}
        var outgoing=ResponseEntity.status(response.code()).header("Cache-Control","no-store").header("X-Accel-Buffering","no");
        for(String name:List.of("Content-Type","X-Trace-Id","X-Agent-Stream-Version")){
            var value=response.header(name);if(value!=null)outgoing.header(name,value);
        }
        return outgoing.body(output->{
            try(response){
                if(response.body()!=null){var input=response.body().byteStream();byte[] chunk=new byte[8192];int size;
                    while((size=input.read(chunk))!=-1){output.write(chunk,0,size);output.flush();}}
            }finally{call.cancel();}
        });
    }
}
