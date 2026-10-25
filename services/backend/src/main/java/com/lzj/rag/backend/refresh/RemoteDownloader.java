package com.lzj.rag.backend.refresh;

import com.lzj.rag.backend.knowledgebase.KnowledgeBaseException;
import com.lzj.rag.backend.storage.StorageProperties;
import jakarta.annotation.PreDestroy;
import okhttp3.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Each hop resolves once, validates every address, and pins that result into the connection's DNS. */
@Component @EnableConfigurationProperties(RemoteProperties.class)
public class RemoteDownloader {
    private final RemoteProperties config;private final StorageProperties storage;
    private final OkHttpClient client;private final Semaphore slots;
    private final ExecutorService resolver;
    public RemoteDownloader(RemoteProperties config,StorageProperties storage) {
        this.config=config;this.storage=storage;slots=new Semaphore(config.concurrency());
        resolver=new ThreadPoolExecutor(config.concurrency(),config.concurrency(),0L,TimeUnit.MILLISECONDS,new SynchronousQueue<>(),r->{var t=new Thread(r,"kb-remote-dns");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
        client=new OkHttpClient.Builder().proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false)
                .retryOnConnectionFailure(false).connectTimeout(config.connectTimeout()).readTimeout(config.readTimeout())
                .callTimeout(config.downloadTimeout()).connectionPool(new ConnectionPool(0,1,TimeUnit.SECONDS))
                .protocols(List.of(Protocol.HTTP_1_1)).build();
    }
    public String validateUrl(String raw) {
        if(!config.enabled())throw error(503,"KB_REMOTE_DISABLED","Remote import is not enabled");
        HttpUrl url=raw==null?null:HttpUrl.parse(raw);
        if(raw==null || raw.length()>2048 || url==null || !url.username().isEmpty() || !url.password().isEmpty() || raw.contains("@")
                || url.fragment()!=null || (!url.isHttps() && !(config.allowHttp() && url.scheme().equals("http")))
                || !config.allowedPorts().contains(url.port()) || config.allowedHosts().stream().noneMatch(h->h.equalsIgnoreCase(url.host())))
            throw error(400,"KB_REMOTE_URL_REJECTED","URL must use an approved exact host, scheme and port, without credentials or fragment");
        return url.toString();
    }
    public record Download(Path path,String hash,long size,String etag,String lastModified,int httpStatus) implements AutoCloseable {
        @Override public void close(){if(path!=null)try{Files.deleteIfExists(path);}catch(IOException ignored){org.slf4j.LoggerFactory.getLogger(RemoteDownloader.class).warn("Remote spool remains eligible for cleanup");}}
        public MultipartFile file(String filename) {return new DiskFile(path,filename,size);}
    }
    public Download download(String raw,String etag,String modified,Instant outerDeadline) {
        String current=validateUrl(raw);if(!slots.tryAcquire())throw error(429,"KB_REMOTE_BUSY","Remote download capacity is full; retry later");
        Path spool=null;Instant deadline=Instant.now().plus(config.downloadTimeout());if(outerDeadline!=null && outerDeadline.isBefore(deadline))deadline=outerDeadline;
        try {
            for(int redirects=0;;redirects++) {
                HttpUrl url=Objects.requireNonNull(HttpUrl.parse(validateUrl(current)));
                List<InetAddress> addresses=resolve(url.host(),deadline);
                for(var address:addresses)if(!permitted(address,config.trustedPrivateHosts().stream().anyMatch(h->h.equalsIgnoreCase(url.host()))))
                    throw error(400,"KB_REMOTE_ADDRESS_REJECTED","Resolved address is not permitted");
                var pinned=client.newBuilder().dns(host->{if(!host.equalsIgnoreCase(url.host()))throw new UnknownHostException("Unexpected host");return addresses;})
                        .callTimeout(remaining(deadline)).build();
                var request=new Request.Builder().url(url).header("Accept-Encoding","identity").header("Connection","close").header("User-Agent","KnowledgeBase-Importer/1");
                if(etag!=null)request.header("If-None-Match",etag);
                if(modified!=null)request.header("If-Modified-Since",modified);
                try(var response=pinned.newCall(request.build()).execute()) {
                    int status=response.code();
                    if(Set.of(301,302,303,307,308).contains(status)) {
                        if(redirects>=config.maxRedirects())throw error(422,"KB_REMOTE_REDIRECT_LIMIT","Remote redirect limit exceeded");
                        String location=response.header("Location");HttpUrl next=location==null?null:url.resolve(location);
                        if(next==null || (url.isHttps() && !next.isHttps()))throw error(400,"KB_REMOTE_URL_REJECTED","Unsafe remote redirect");
                        current=validateUrl(next.toString());
                        // Validators are scoped to a resource URI, even when a redirect stays on the same origin.
                        etag=null;modified=null;
                        continue;
                    }
                    String nextEtag=header(response.header("ETag"),512),nextModified=header(response.header("Last-Modified"),128);
                    // We persist only the configured source URI. Redirected sources use hash comparison until
                    // an effective-URI baseline is supported, rather than attach target validators to the source.
                    if(redirects>0){nextEtag=null;nextModified=null;}
                    if(status==304) {
                        if(etag==null && modified==null)throw error(502,"KB_REMOTE_INVALID_RESPONSE","Unsolicited 304 response");
                        return new Download(null,null,0,nextEtag,nextModified,304);
                    }
                    if(status!=200)throw error(status==429?429:502,status==429?"KB_REMOTE_RATE_LIMITED":status>=500?"KB_REMOTE_HTTP_ERROR":"KB_REMOTE_HTTP_REJECTED","Remote server did not return a complete file");
                    if(!response.header("Content-Encoding","identity").equalsIgnoreCase("identity"))throw error(415,"KB_REMOTE_ENCODING_UNSUPPORTED","Remote content encoding must be identity");
                    var body=response.body();long limit=Math.min(config.maxDownloadBytes(),storage.maxUploadBytes());
                    if(body==null)throw error(502,"KB_REMOTE_INVALID_RESPONSE","Remote response has no body");
                    if(body.contentLength()>limit)throw error(413,"KB_UPLOAD_TOO_LARGE","Remote file exceeds configured limit");
                    var directory=storage.tempDirectory();if(directory!=null && !directory.toString().isBlank()){Files.createDirectories(directory);spool=Files.createTempFile(directory,"kb-remote-",".tmp");}
                    else spool=Files.createTempFile("kb-remote-",".tmp");
                    var digest=MessageDigest.getInstance("SHA-256");long size=0;
                    try(var in=body.byteStream();var out=Files.newOutputStream(spool)) {
                        byte[] buffer=new byte[8192];int count;
                        while((count=in.read(buffer))!=-1){remaining(deadline);if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();size+=count;if(size>limit)throw error(413,"KB_UPLOAD_TOO_LARGE","Remote file exceeds configured limit");digest.update(buffer,0,count);out.write(buffer,0,count);}
                    }
                    if(size==0)throw error(400,"KB_EMPTY_FILE","Remote file is empty");
                    var result=new Download(spool,HexFormat.of().formatHex(digest.digest()),size,nextEtag,nextModified,200);spool=null;return result;
                }
            }
        }catch(KnowledgeBaseException e){throw e;}
        catch(InterruptedIOException e){throw error(504,"KB_REMOTE_TIMEOUT","Remote download exceeded its time budget");}
        catch(Exception e){throw error(502,"KB_REMOTE_DOWNLOAD_FAILED","Remote download failed; source and network details are omitted");}
        finally {slots.release();if(spool!=null)try{Files.deleteIfExists(spool);}catch(IOException ignored){ }}
    }
    private List<InetAddress> resolve(String host,Instant deadline)throws Exception {
        Future<List<InetAddress>> task;
        try{task=resolver.submit(()->List.of(InetAddress.getAllByName(host)));}catch(RejectedExecutionException e){throw error(429,"KB_REMOTE_BUSY","DNS resolution capacity is full");}
        try {var result=task.get(Math.min(remaining(deadline).toMillis(),config.connectTimeout().toMillis()),TimeUnit.MILLISECONDS);if(result.isEmpty())throw new UnknownHostException();return result;}
        catch(TimeoutException e){throw error(504,"KB_REMOTE_TIMEOUT","DNS resolution exceeded its time budget");}
        finally{task.cancel(true);}
    }
    static boolean permitted(InetAddress address,boolean trustedPrivate) {
        byte[] b=address.getAddress();
        if(address.isAnyLocalAddress() || address.isMulticastAddress() || address.isLinkLocalAddress())return false;
        if(b.length==4) {
            int a=b[0]&255,c=b[1]&255,d=b[2]&255;
            if(a==169 && c==254 || a==168 && c==63 && d==129 && (b[3]&255)==16)return false;
            if(trustedPrivate)return true;
            return !(a==0 || a==10 || a==127 || a>=224 || a==100 && c>=64 && c<=127 || a==172 && c>=16 && c<=31
                    || a==192 && (c==168 || c==0 || c==2) || a==198 && (c==18 || c==19 || c==51 && d==100) || a==203 && c==0 && d==113);
        }
        if(trustedPrivate)return true;
        // Only native global unicast, excluding documentation, transition and special-purpose ranges.
        return (b[0]&0xe0)==0x20 && !((b[0]&255)==0x20 && (b[1]&255)==0x02)
                && !((b[0]&255)==0x20 && (b[1]&255)==1 && (((b[2]&255)==0x0d && (b[3]&255)==0xb8) || (b[2]&255)<2));
    }
    private static Duration remaining(Instant deadline){var duration=Duration.between(Instant.now(),deadline);if(duration.toMillis()<1)throw error(504,"KB_REMOTE_TIMEOUT","Remote download exceeded its time budget");return duration;}
    private static String header(String value,int max){if(value==null)return null;if(value.length()>max || value.codePoints().anyMatch(c->c<32 || c>126))throw error(502,"KB_REMOTE_INVALID_RESPONSE","Remote validator header is invalid");return value;}
    private static KnowledgeBaseException error(int status,String code,String message){return new KnowledgeBaseException(status,code,message);}
    @PreDestroy void close(){resolver.shutdownNow();client.connectionPool().evictAll();client.dispatcher().executorService().shutdownNow();}
    private record DiskFile(Path path,String filename,long size) implements MultipartFile {
        public String getName(){return "file";} public String getOriginalFilename(){return filename;}public String getContentType(){return "application/octet-stream";}
        public boolean isEmpty(){return size==0;}public long getSize(){return size;}public byte[] getBytes(){throw new UnsupportedOperationException("Use bounded streaming");}
        public InputStream getInputStream()throws IOException{return Files.newInputStream(path);}public void transferTo(File destination)throws IOException{Files.copy(path,destination.toPath(),StandardCopyOption.REPLACE_EXISTING);}
    }
}
