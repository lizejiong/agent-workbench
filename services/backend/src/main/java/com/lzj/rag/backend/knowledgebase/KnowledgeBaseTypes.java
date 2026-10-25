package com.lzj.rag.backend.knowledgebase;

import jakarta.validation.constraints.*;

import java.time.OffsetDateTime;
import java.util.UUID;

public final class KnowledgeBaseTypes {
    private KnowledgeBaseTypes() { }

    public record CreateRequest(@NotBlank @Size(max = 128) @Pattern(regexp = "[^\\x00]*") String name,
                                @Size(max = 2000) @Pattern(regexp = "[^\\x00]*") String description,
                                @Size(max = 128) String embeddingProfileId) { }

    /** PUT replaces mutable metadata; version is an explicit compare-and-set precondition. */
    public record UpdateRequest(@NotBlank @Size(max = 128) @Pattern(regexp = "[^\\x00]*") String name,
                                @Size(max = 2000) @Pattern(regexp = "[^\\x00]*") String description,
                                @NotNull @Positive Long version) { }

    public record PatchRequest(@NotNull @Positive Long version,
                               @Size(min = 1, max = 128) @Pattern(regexp = "[^\\x00]*") String name,
                               @Size(max = 2000) @Pattern(regexp = "[^\\x00]*") String description,
                               @Size(max = 128) String embeddingProfileId) { }
    public record BindRequest(@NotNull @Positive Long version, @NotBlank String embeddingProfileId) { }
    public record View(UUID id, String name, String description, long version,
                       OffsetDateTime createdAt, OffsetDateTime updatedAt,
                       String bindingStatus, String embeddingProfileId, String embeddingModelId,
                       String embeddingProvider, Integer embeddingDimension, String embeddingConfigRevision,
                       String vectorNamespace, String embeddingAiConfigRevision, long documentCount) {
        static View from(KnowledgeBase base) {
            return from(base,0);
        }
        static View from(KnowledgeBase base,long documentCount) {
            return new View(base.getId(), base.getName(), base.getDescription(), base.getVersion(),
                    base.getCreatedAt(), base.getUpdatedAt(), base.getEmbeddingModelId() == null ? "UNBOUND" : "BOUND",
                    base.getEmbeddingProfileId(), base.getEmbeddingModelId(), base.getEmbeddingProvider(),
                    base.getEmbeddingDimension(), base.getEmbeddingConfigRevision(), base.getVectorNamespace(), base.getEmbeddingAiConfigRevision(),documentCount);
        }
    }
}
