package com.lzj.rag.backend;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;

public final class MineruStub implements AutoCloseable {
    public volatile String mode="GOOD";
    public volatile String multipart;
    private final HttpServer server;private final ExecutorService executor=Executors.newFixedThreadPool(2);
    public MineruStub() {
        try {server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);}catch(java.io.IOException e){throw new IllegalStateException(e);}
        server.setExecutor(executor);server.createContext("/",exchange->{
            try(exchange) {
                var json=new ObjectMapper();Object response;int status=200;
                if(exchange.getRequestURI().getPath().equals("/openapi.json")) {
                    var properties=new LinkedHashMap<String,Object>();for(String key:List.of("files","return_content_list","return_images","return_md","response_format_zip","backend"))properties.put(key,Map.of("type","string"));
                    response=mode.equals("WRONG_API")?Map.of("paths",Map.of()):Map.of("paths",Map.of("/file_parse",Map.of("post",Map.of("requestBody",Map.of("content",Map.of("multipart/form-data",Map.of("schema",Map.of("properties",properties))))))));
                }else {
                    multipart=new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.ISO_8859_1);
                    if(mode.equals("TIMEOUT"))try{Thread.sleep(2000);}catch(InterruptedException e){Thread.currentThread().interrupt();}
                    if(mode.equals("UNAVAILABLE")){response=Map.of("error","private upstream secret");status=503;}
                    else {
                        var content=List.of(Map.of("type","text","text","Invoice","text_level",1,"page_idx",0,"bbox",List.of(0,0,1000,100)),
                                Map.of("type","text","text","INVOICE 42; Apples: 2.","page_idx",0,"bbox",List.of(0,100,1000,200)),
                                Map.of("type","table","page_idx",0,"table_body","<table><tr><th>项目</th><th>数量</th></tr><tr><td rowspan='2'>苹果</td><td>2</td></tr><tr><td>3</td></tr></table>"));
                        String encoded=json.writeValueAsString(content);if(mode.equals("BAD_SPAN"))encoded=encoded.replace("rowspan='2'","rowspan='4'");
                        response=Map.of("version",mode.equals("VERSION")?"4.0.0":"2.7.6","backend","pipeline","results",Map.of("document",Map.of("content_list",mode.equals("EMPTY")?"[]":encoded)));
                    }
                }
                byte[] bytes=json.writeValueAsBytes(response);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);
            }catch(java.io.IOException ignored){ }
        });server.start();
    }
    public String url(){return "http://127.0.0.1:"+server.getAddress().getPort();}
    public void close(){server.stop(0);executor.shutdownNow();}
}
