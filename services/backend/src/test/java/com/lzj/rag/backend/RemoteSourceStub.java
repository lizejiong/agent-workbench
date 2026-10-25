package com.lzj.rag.backend;
import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Controlled origin only; exercise actual HTTP conditionals, redirects and download failure. */
public final class RemoteSourceStub implements AutoCloseable {
    private final HttpServer server;private final ExecutorService threads=Executors.newFixedThreadPool(4);
    public volatile String body="# Database\n\nPostgreSQL connection backup.";
    public volatile String etag="\"v1\"",modified="Wed, 21 Oct 2026 08:00:00 GMT",mode="GOOD",lastIfNoneMatch,lastIfModifiedSince;
    public volatile Runnable beforeResponse=()->{};public final AtomicInteger calls=new AtomicInteger();
    public RemoteSourceStub(){try{server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(threads);server.createContext("/",this::handle);server.start();}catch(Exception e){throw new IllegalStateException(e);}}
    public String url(){return "http://127.0.0.1:"+server.getAddress().getPort()+"/document.md";}
    public int port(){return server.getAddress().getPort();}
    public void reset(){body="# Database\n\nPostgreSQL connection backup.";etag="\"v1\"";modified="Wed, 21 Oct 2026 08:00:00 GMT";mode="GOOD";beforeResponse=()->{};calls.set(0);lastIfNoneMatch=null;lastIfModifiedSince=null;}
    private void handle(HttpExchange exchange)throws java.io.IOException {
        try {
            calls.incrementAndGet();lastIfNoneMatch=exchange.getRequestHeaders().getFirst("If-None-Match");lastIfModifiedSince=exchange.getRequestHeaders().getFirst("If-Modified-Since");beforeResponse.run();
            if(mode.equals("REDIRECT")){exchange.getResponseHeaders().add("Location","http://169.254.169.254/latest/meta-data/");exchange.sendResponseHeaders(302,-1);return;}
            if(mode.equals("LOCAL_REDIRECT") && !exchange.getRequestURI().getPath().equals("/final.md")){exchange.getResponseHeaders().add("Location","/final.md");exchange.sendResponseHeaders(302,-1);return;}
            if(mode.equals("LOOP")){exchange.getResponseHeaders().add("Location","/again.md");exchange.sendResponseHeaders(302,-1);return;}
            if(mode.equals("FAIL")){exchange.sendResponseHeaders(503,-1);return;}
            if(mode.equals("SLOW"))try{Thread.sleep(2000);}catch(InterruptedException ignored){Thread.currentThread().interrupt();}
            if(mode.equals("LENGTH")){exchange.sendResponseHeaders(200,10000000);return;}
            if(etag!=null)exchange.getResponseHeaders().add("ETag",etag);
            if(modified!=null)exchange.getResponseHeaders().add("Last-Modified",modified);
            if((mode.equals("GOOD") || mode.equals("LOCAL_REDIRECT")) && etag!=null && etag.equals(lastIfNoneMatch)){exchange.sendResponseHeaders(304,-1);return;}
            byte[] bytes=(mode.equals("EMPTY")?"":body).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type","text/markdown");exchange.sendResponseHeaders(200,bytes.length==0?-1:0);exchange.getResponseBody().write(bytes);
        }finally{exchange.close();}
    }
    public void close(){server.stop(0);threads.shutdownNow();}
}
