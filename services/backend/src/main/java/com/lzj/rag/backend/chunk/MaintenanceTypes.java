package com.lzj.rag.backend.chunk;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.*;

public final class MaintenanceTypes {
    private MaintenanceTypes() { }
    public record ContentRequest(@NotNull @Positive Long version,@NotBlank @Size(max=131072) String content) { }
    public record EnabledRequest(@NotNull @Positive Long version,@NotNull Boolean enabled) { }
    public record BatchRequest(@NotNull @Positive Long version,@NotNull Boolean enabled,
            List<@NotNull UUID> chunkIds,boolean applyAll) { }
    public record ChunkView(UUID id,UUID documentId,UUID kbId,long generation,int chunkIndex,String content,
            String embeddingText,String contentHash,int charCount,Integer tokenCount,boolean tokenCountEstimated,
            boolean enabled,boolean edited,Map<String,Object> metadata,String createdBy,String updatedBy,
            OffsetDateTime createdAt,OffsetDateTime updatedAt) { }
    public record Changed(long documentVersion,long manualRevision,long chunkCount,List<ChunkView> chunks) { }
}
