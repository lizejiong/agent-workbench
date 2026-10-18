package com.lzj.rag.ai.service;

public class AiException extends RuntimeException {
    private final int status;
    private final String category;
    private final boolean retryable;
    private final boolean counted;
    public AiException(int status, String category, String message, boolean retryable, boolean counted) {
        super(message); this.status = status; this.category = category; this.retryable = retryable; this.counted = counted;
    }
    public int status() { return status; }
    public String category() { return category; }
    public boolean retryable() { return retryable; }
    public boolean counted() { return counted; }
    public static AiException input(String message) { return new AiException(400, "INVALID_INPUT", message, false, false); }
    public static AiException unavailable(String message) { return new AiException(503, "UNAVAILABLE", message, true, false); }
    public static AiException invalid() { return new AiException(502, "INVALID_RESPONSE", "Invalid upstream response", true, true); }
    public static AiException timeout() { return new AiException(504, "TIMEOUT", "AI request timed out", true, true); }
    public static AiException classify(Throwable error) {
        if (error instanceof java.util.concurrent.CancellationException || reactor.core.Exceptions.isCancel(error))
            throw reactor.core.Exceptions.propagate(error);
        if (error instanceof AiException e) return e;
        if (error instanceof java.util.concurrent.TimeoutException) return timeout();
        if (error instanceof org.springframework.core.io.buffer.DataBufferLimitException
                || error instanceof org.springframework.core.codec.DecodingException) return invalid();
        if (error instanceof org.springframework.web.reactive.function.client.WebClientRequestException
                || error instanceof java.io.IOException)
            return new AiException(502, "NETWORK", "Upstream connection failed", true, true);
        return new AiException(500, "INTERNAL", "Internal AI service error", false, false);
    }
}
