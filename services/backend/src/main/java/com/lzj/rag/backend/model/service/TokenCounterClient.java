package com.lzj.rag.backend.model.service;

import java.time.Instant;

public interface TokenCounterClient {
    record Estimate(long tokens,boolean estimated) {}
    Estimate estimate(String text,String requestId,Instant deadline);
}
