package com.lzj.rag.backend.document;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.UUID;

public final class DocumentTypes {
    private DocumentTypes() { }

    public record IngestionSpec(@Min(1) @Max(1) Integer schemaVersion,
            @Pattern(regexp = "AUTO|TEXT|MARKDOWN") String parser,
            @Valid Splitter splitter,@Pattern(regexp="fast|fidelity") String parseProfile) {
        public IngestionSpec(Integer schemaVersion,String parser,Splitter splitter){this(schemaVersion,parser,splitter,null);}
        public IngestionSpec {
            if (schemaVersion == null) schemaVersion = 1;
            if (parser == null) parser = "AUTO";
            if (splitter == null) splitter = new Splitter(null, null, null);
            if (parseProfile == null) parseProfile = "fast";
        }
        public static IngestionSpec defaults() { return new IngestionSpec(1, "AUTO", null); }
    }
    public record Splitter(@Pattern(regexp = "STRUCTURAL|WHOLE_DOCUMENT") String kind,
            @Min(128) @Max(4000) Integer maxChars, @Min(0) @Max(1000) Integer overlapChars,
            @Min(1) @Max(1000) Integer tableRows, @Min(0) @Max(4096) Integer toleranceChars) {
        public Splitter(String kind,Integer maxChars,Integer overlapChars) { this(kind,maxChars,overlapChars,null,null); }
        public Splitter {
            if (kind == null) kind = "STRUCTURAL";
            if (maxChars == null) maxChars = 1024;
            if (overlapChars == null) overlapChars = 128;
            if (tableRows == null) tableRows = 50;
            if (toleranceChars == null) toleranceChars = 512;
        }
        @AssertTrue(message = "overlapChars must be less than maxChars")
        @com.fasterxml.jackson.annotation.JsonIgnore
        public boolean isOverlapValid() { return overlapChars < maxChars; }
    }
    public record UploadMetadata(@Size(max = 255) String name, @Valid IngestionSpec ingestionSpec) {
        public UploadMetadata {
            if (ingestionSpec == null) ingestionSpec = IngestionSpec.defaults();
        }
    }
    public record PatchRequest(@NotNull @Positive Long version, @Size(max = 255) String name,
                               @Valid IngestionSpec ingestionSpec) { }
    public record View(UUID id, UUID kbId, String name, String originalFilename, String sourceType,
            String objectKey, String mimeType, long fileSize, String contentHash, boolean enabled,
            String processStatus, IngestionSpec ingestionSpec, long configRevision, Long activeGeneration,
            long chunkCount, long version, long manualRevision, boolean hasManualChanges, OffsetDateTime createdAt, OffsetDateTime updatedAt,String sourceUrl,OffsetDateTime lastIngestedAt) { }
    public record Uploaded(View document, boolean replayed) { }
}
