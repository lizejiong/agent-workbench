package com.lzj.rag.backend.model;
import java.time.Instant;

public interface VlmClient {
    record Description(String text,String model) { }
    Description describe(byte[] image,String mime,String requestId,Instant deadline);
}
