package com.lzj.rag.backend.chunk;
import java.util.Map;
import java.util.UUID;

public record Chunk(UUID id, int chunkIndex, String content, String embeddingText, String contentHash,
                    int charCount, Map<String,Object> metadata) { }
