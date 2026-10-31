package com.lzj.rag.backend.refresh.download;

import com.lzj.rag.backend.document.service.bo.UploadSourceBO;
import com.lzj.rag.backend.infra.storage.config.StorageProperties;
import com.lzj.rag.backend.knowledgebase.exception.KnowledgeBaseException;
import com.lzj.rag.backend.refresh.config.RemoteProperties;

import jakarta.annotation.PreDestroy;

import okhttp3.*;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** 逐跳校验 URL 与解析地址，并将已验证 DNS 结果固定到本次连接。 */
@Component
@EnableConfigurationProperties(RemoteProperties.class)
public class RemoteDownloader {

    /** 远程导入的网络许可、下载容量、并发、租约和重试配置。 */
    private final RemoteProperties config;

    /** 对象存储连接、文件容量、临时目录及上传补偿配置。 */
    private final StorageProperties storage;

    /** 禁止隐式重定向并受网络许可规则约束的 HTTP 客户端。 */
    private final OkHttpClient client;

    /** 限制当前业务并发执行数量的许可集合。 */
    private final Semaphore slots;

    /** 有界 DNS 解析线程池。 */
    private final ExecutorService resolver;

    /**
     * 初始化当前组件，保存业务依赖及本次使用的配置。
     *
     * @param config 远程导入的网络许可、下载容量、并发、租约和重试配置。
     * @param storage 对象存储连接、文件容量、临时目录及上传补偿配置。
     */
    public RemoteDownloader(RemoteProperties config, StorageProperties storage) {
        this.config = config;
        this.storage = storage;
        slots = new Semaphore(config.concurrency());
        resolver =
                new ThreadPoolExecutor(
                        config.concurrency(),
                        config.concurrency(),
                        0L,
                        TimeUnit.MILLISECONDS,
                        new SynchronousQueue<>(),
                        r -> {
                            // r：交给 DNS 解析线程执行的工作任务。
                            // t：用于 DNS 解析的守护线程。
                            var t = new Thread(r, "kb-remote-dns");
                            t.setDaemon(true);
                            return t;
                        },
                        new ThreadPoolExecutor.AbortPolicy());
        client =
                new OkHttpClient.Builder()
                        .proxy(Proxy.NO_PROXY)
                        .followRedirects(false)
                        .followSslRedirects(false)
                        .retryOnConnectionFailure(false)
                        .connectTimeout(config.connectTimeout())
                        .readTimeout(config.readTimeout())
                        .callTimeout(config.downloadTimeout())
                        .connectionPool(new ConnectionPool(0, 1, TimeUnit.SECONDS))
                        .protocols(List.of(Protocol.HTTP_1_1))
                        .build();
    }

    /**
     * 校验精确主机、协议和端口许可，拒绝凭据、片段和不合法地址。
     *
     * @param raw 尚未校验或规范化的原始输入。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public String validateUrl(String raw) {
        if (!config.enabled())
            throw error(503, "KB_REMOTE_DISABLED", "Remote import is not enabled");
        // url：经过协议、主机和端口许可校验的远程地址。
        HttpUrl url = raw == null ? null : HttpUrl.parse(raw);
        // h：配置中允许访问或信任内网的精确主机名称。
        if (raw == null
                || raw.length() > 2048
                || url == null
                || !url.username().isEmpty()
                || !url.password().isEmpty()
                || raw.contains("@")
                || url.fragment() != null
                || (!url.isHttps() && !(config.allowHttp() && url.scheme().equals("http")))
                || !config.allowedPorts().contains(url.port())
                || config.allowedHosts().stream().noneMatch(h -> h.equalsIgnoreCase(url.host())))
            throw error(
                    400,
                    "KB_REMOTE_URL_REJECTED",
                    "URL must use an approved exact host, scheme and port, without credentials or fragment");
        return url.toString();
    }

    /**
     * 下载临时文件及 HTTP 校验标识，关闭时删除本地文件。
     *
     * @param path 当前需要读取、写入或清理的文件路径。
     * @param hash 源内容或切片正文的稳定摘要。
     * @param size 下载源文件的实际字节数。
     * @param etag 远程资源的 ETag 条件请求标识。
     * @param lastModified 远程资源返回的最近修改时间标识。
     * @param httpStatus 远程源请求返回的 HTTP 状态。
     */
    public record Download(
            Path path, String hash, long size, String etag, String lastModified, int httpStatus)
            implements AutoCloseable {

        /** 释放当前对象持有的临时文件或执行资源。 */
        @Override
        public void close() {
            if (path != null)
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 临时文件删除失败不替换下载结果，记录告警以便后续清理。
                    org.slf4j.LoggerFactory.getLogger(RemoteDownloader.class)
                            .warn("Remote spool remains eligible for cleanup");
                }
        }

        /**
         * 将临时下载文件包装为与 HTTP 无关的文件输入，复用上传校验流程。
         *
         * @param filename 经过业务校验的文件名。
         * @return 当前业务处理结果；失败时通过异常报告。
         */
        public UploadSourceBO file(String filename) {
            return new UploadSourceBO(filename, size, () -> Files.newInputStream(path));
        }
    }

    /**
     * 逐跳验证并固定 DNS 地址，在截止时间和字节上限内流式下载源文件。
     *
     * @param raw 尚未校验或规范化的原始输入。
     * @param etag 远程资源的 ETag 条件请求标识。
     * @param modified 远程资源的 Last-Modified 条件请求标识。
     * @param outerDeadline 上层调用传入的总截止时间。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public Download download(String raw, String etag, String modified, Instant outerDeadline) {
        // current：当前待下载的 URL，每次重定向后重新校验。
        String current = validateUrl(raw);
        if (!slots.tryAcquire())
            throw error(429, "KB_REMOTE_BUSY", "Remote download capacity is full; retry later");
        // spool：当前尝试持有的磁盘临时文件。
        Path spool = null;
        // deadline：本阶段或本次请求必须完成的截止时间。
        Instant deadline = Instant.now().plus(config.downloadTimeout());
        if (outerDeadline != null && outerDeadline.isBefore(deadline)) deadline = outerDeadline;
        try {
            // redirects：本次下载已经处理的重定向次数。
            for (int redirects = 0; ; redirects++) {
                // url：经过协议、主机和端口许可校验的远程地址。
                HttpUrl url = Objects.requireNonNull(HttpUrl.parse(validateUrl(current)));
                // addresses：本次 DNS 查询返回且待校验的全部地址。
                List<InetAddress> addresses = resolve(url.host(), deadline);
                // address：当前需要校验的网络地址。
                for (var address : addresses) /*
 * h：配置中允许访问或信任内网的精确主机名称。
 */
                    if (!permitted(
                            address,
                            config.trustedPrivateHosts().stream()
                                    .anyMatch(h -> h.equalsIgnoreCase(url.host()))))
                        throw error(
                                400,
                                "KB_REMOTE_ADDRESS_REJECTED",
                                "Resolved address is not permitted");
                // pinned：固定已验证 DNS 地址后构建的专用 HTTP 客户端。
                var pinned =
                        client.newBuilder()
                                .dns(
                                        host -> {
                                            // host：当前连接要求匹配的主机名称。
                                            if (!host.equalsIgnoreCase(url.host()))
                                                throw new UnknownHostException("Unexpected host");
                                            return addresses;
                                        })
                                .callTimeout(remaining(deadline))
                                .build();
                // request：当前入口接收的请求参数。
                var request =
                        new Request.Builder()
                                .url(url)
                                .header("Accept-Encoding", "identity")
                                .header("Connection", "close")
                                .header("User-Agent", "KnowledgeBase-Importer/1");
                if (etag != null) request.header("If-None-Match", etag);
                if (modified != null) request.header("If-Modified-Since", modified);
                // response：当前 HTTP 响应或外部服务返回结果。
                try (var response = pinned.newCall(request.build()).execute()) {
                    // status：本次外部 HTTP 响应或错误映射使用的状态码。
                    int status = response.code();
                    if (Set.of(301, 302, 303, 307, 308).contains(status)) {
                        if (redirects >= config.maxRedirects())
                            throw error(
                                    422,
                                    "KB_REMOTE_REDIRECT_LIMIT",
                                    "Remote redirect limit exceeded");
                        // location：当前结构的来源位置或远程重定向目标。
                        String location = response.header("Location");
                        // next：下一次触发时间或下一跳远程地址。
                        HttpUrl next = location == null ? null : url.resolve(location);
                        if (next == null || (url.isHttps() && !next.isHttps()))
                            throw error(400, "KB_REMOTE_URL_REJECTED", "Unsafe remote redirect");
                        current = validateUrl(next.toString());
                        // 条件请求标识属于具体资源 URI，即使同源重定向，也不能继续沿用原资源标识。
                        etag = null;
                        modified = null;
                        continue;
                    }
                    // nextEtag：本次响应可保存为后续基线的 ETag。
                    String nextEtag = header(response.header("ETag"), 512);
                    // nextModified：本次响应可保存为后续基线的 Last-Modified。
                    String nextModified = header(response.header("Last-Modified"), 128);
                    // 当前只持久化配置的源 URI；重定向后的内容通过摘要比较，
                    // 避免将目标资源的条件标识错误绑定到原始源地址。
                    if (redirects > 0) {
                        nextEtag = null;
                        nextModified = null;
                    }
                    if (status == 304) {
                        if (etag == null && modified == null)
                            throw error(
                                    502, "KB_REMOTE_INVALID_RESPONSE", "Unsolicited 304 response");
                        return new Download(null, null, 0, nextEtag, nextModified, 304);
                    }
                    if (status != 200)
                        throw error(
                                status == 429 ? 429 : 502,
                                status == 429
                                        ? "KB_REMOTE_RATE_LIMITED"
                                        : status >= 500
                                                ? "KB_REMOTE_HTTP_ERROR"
                                                : "KB_REMOTE_HTTP_REJECTED",
                                "Remote server did not return a complete file");
                    if (!response.header("Content-Encoding", "identity")
                            .equalsIgnoreCase("identity"))
                        throw error(
                                415,
                                "KB_REMOTE_ENCODING_UNSUPPORTED",
                                "Remote content encoding must be identity");
                    // body：需要发送或当前接收的 HTTP 正文。
                    var body = response.body();
                    // limit：当前查询数量、下载字节数或解析字符预算的上限。
                    long limit = Math.min(config.maxDownloadBytes(), storage.maxUploadBytes());
                    if (body == null)
                        throw error(
                                502, "KB_REMOTE_INVALID_RESPONSE", "Remote response has no body");
                    if (body.contentLength() > limit)
                        throw error(
                                413, "KB_UPLOAD_TOO_LARGE", "Remote file exceeds configured limit");
                    // directory：临时文件所在目录。
                    var directory = storage.tempDirectory();
                    if (directory != null && !directory.toString().isBlank()) {
                        Files.createDirectories(directory);
                        spool = Files.createTempFile(directory, "kb-remote-", ".tmp");
                    } else spool = Files.createTempFile("kb-remote-", ".tmp");
                    // digest：用于流式累计 SHA-256 摘要的计算器。
                    var digest = MessageDigest.getInstance("SHA-256");
                    // size：分页容量或当前数据的字节数。
                    long size = 0;
                    // out：当前结果集合或文件输出流。
                    // in：当前文件或响应的输入流，使用结束后关闭。
                    try (var in = body.byteStream();
                            var out = Files.newOutputStream(spool)) {
                        // buffer：用于分块读写的固定大小缓冲区。
                        byte[] buffer = new byte[8192];
                        // count：当前累计数量或本次流读取的有效字节数。
                        int count;
                        while ((count = in.read(buffer)) != -1) {
                            remaining(deadline);
                            if (Thread.currentThread().isInterrupted())
                                throw new InterruptedIOException();
                            size += count;
                            if (size > limit)
                                throw error(
                                        413,
                                        "KB_UPLOAD_TOO_LARGE",
                                        "Remote file exceeds configured limit");
                            digest.update(buffer, 0, count);
                            out.write(buffer, 0, count);
                        }
                    }
                    if (size == 0) throw error(400, "KB_EMPTY_FILE", "Remote file is empty");
                    // result：当前步骤产生的处理结果。
                    var result =
                            new Download(
                                    spool,
                                    HexFormat.of().formatHex(digest.digest()),
                                    size,
                                    nextEtag,
                                    nextModified,
                                    200);
                    spool = null;
                    return result;
                }
            }
        } catch (KnowledgeBaseException e) {
            // e：本次捕获或匹配的异常，用于保留业务分类及失败原因。
            throw e;
        } catch (InterruptedIOException e) {
            // e：本次捕获或匹配的异常，用于保留业务分类及失败原因。
            throw error(504, "KB_REMOTE_TIMEOUT", "Remote download exceeded its time budget");
        } catch (Exception e) {
            // e：本次捕获或匹配的异常，用于保留业务分类及失败原因。
            throw error(
                    502,
                    "KB_REMOTE_DOWNLOAD_FAILED",
                    "Remote download failed; source and network details are omitted");
        } finally {
            slots.release();
            if (spool != null)
                try {
                    Files.deleteIfExists(spool);
                } catch (IOException ignored) {
                    // 临时文件清理失败不覆盖下载异常或已返回的下载结果。
                }
        }
    }

    /**
     * 使用有界线程池解析 DNS，并以连接预算及总截止时间限制等待。
     *
     * @param host 当前连接要求匹配的主机名称。
     * @param deadline 本阶段或本次请求必须完成的截止时间。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private List<InetAddress> resolve(String host, Instant deadline) throws Exception {
        // task：当前异步操作的可等待结果，超时或失去执行权时用于取消任务。
        Future<List<InetAddress>> task;
        try {
            task = resolver.submit(() -> List.of(InetAddress.getAllByName(host)));
        } catch (RejectedExecutionException e) {
            // e：本次捕获或匹配的异常，用于保留业务分类及失败原因。
            throw error(429, "KB_REMOTE_BUSY", "DNS resolution capacity is full");
        }
        try {
            // result：当前步骤产生的处理结果。
            var result =
                    task.get(
                            Math.min(
                                    remaining(deadline).toMillis(),
                                    config.connectTimeout().toMillis()),
                            TimeUnit.MILLISECONDS);
            if (result.isEmpty()) throw new UnknownHostException();
            return result;
        } catch (TimeoutException e) {
            // e：本次捕获或匹配的异常，用于保留业务分类及失败原因。
            throw error(504, "KB_REMOTE_TIMEOUT", "DNS resolution exceeded its time budget");
        } finally {
            task.cancel(true);
        }
    }

    /**
     * 按 IPv4 或 IPv6 地址范围拒绝特殊用途地址，并应用显式内网信任配置。
     *
     * @param address 当前需要校验的网络地址。
     * @param trustedPrivate 当前主机是否被显式允许访问内网地址。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    static boolean permitted(InetAddress address, boolean trustedPrivate) {
        // b：用于检查地址前缀及特殊网段的原始地址字节。
        byte[] b = address.getAddress();
        if (address.isAnyLocalAddress()
                || address.isMulticastAddress()
                || address.isLinkLocalAddress()) return false;
        if (b.length == 4) {
            // a：IPv4 地址第一段的无符号数值。
            int a = b[0] & 255;
            // c：IPv4 地址第二段的无符号数值。
            int c = b[1] & 255;
            // d：IPv4 地址第三段的无符号数值。
            int d = b[2] & 255;
            if (a == 169 && c == 254 || a == 168 && c == 63 && d == 129 && (b[3] & 255) == 16)
                return false;
            if (trustedPrivate) return true;
            return !(a == 0
                    || a == 10
                    || a == 127
                    || a >= 224
                    || a == 100 && c >= 64 && c <= 127
                    || a == 172 && c >= 16 && c <= 31
                    || a == 192 && (c == 168 || c == 0 || c == 2)
                    || a == 198 && (c == 18 || c == 19 || c == 51 && d == 100)
                    || a == 203 && c == 0 && d == 113);
        }
        if (trustedPrivate) return true;
        // 只接受原生全球单播地址，排除文档示例、过渡机制和特殊用途地址段。
        return (b[0] & 0xe0) == 0x20
                && !((b[0] & 255) == 0x20 && (b[1] & 255) == 0x02)
                && !((b[0] & 255) == 0x20
                        && (b[1] & 255) == 1
                        && (((b[2] & 255) == 0x0d && (b[3] & 255) == 0xb8) || (b[2] & 255) < 2));
    }

    /**
     * 计算剩余下载预算，不足一毫秒时结束调用。
     *
     * @param deadline 本阶段或本次请求必须完成的截止时间。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private static Duration remaining(Instant deadline) {
        // duration：当前计算得到的时间间隔。
        var duration = Duration.between(Instant.now(), deadline);
        if (duration.toMillis() < 1)
            throw error(504, "KB_REMOTE_TIMEOUT", "Remote download exceeded its time budget");
        return duration;
    }

    /**
     * 校验远程条件请求头的长度和可打印 ASCII 字符。
     *
     * @param value 当前待校验、转换或写入的值。
     * @param max 当前校验允许的最大长度或数值。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private static String header(String value, int max) {
        if (value == null) return null;
        // c：当前处理的 Unicode 码点或 SAX 字符片段。
        if (value.length() > max || value.codePoints().anyMatch(c -> c < 32 || c > 126))
            throw error(502, "KB_REMOTE_INVALID_RESPONSE", "Remote validator header is invalid");
        return value;
    }

    /**
     * 按给定状态及稳定分类创建远程下载异常。
     *
     * @param status 本次外部 HTTP 响应或错误映射使用的状态码。
     * @param code 对外使用的稳定业务错误分类。
     * @param message 对外展示的业务错误或状态说明。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private static KnowledgeBaseException error(int status, String code, String message) {
        return new KnowledgeBaseException(status, code, message);
    }

    /** 释放当前对象持有的临时文件或执行资源。 */
    @PreDestroy
    void close() {
        resolver.shutdownNow();
        client.connectionPool().evictAll();
        client.dispatcher().executorService().shutdownNow();
    }
}
