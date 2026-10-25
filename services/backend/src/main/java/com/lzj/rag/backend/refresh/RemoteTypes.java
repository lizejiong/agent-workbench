package com.lzj.rag.backend.refresh;
import com.lzj.rag.backend.document.DocumentTypes;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.UUID;

public final class RemoteTypes {
    private RemoteTypes() { }
    public record ImportRequest(@NotBlank @Size(max=2048) String url,@NotBlank @Size(max=255) String filename,
            @Size(max=255) String name,@Valid DocumentTypes.IngestionSpec ingestionSpec,boolean ingest) {
        public ImportRequest {if(ingestionSpec==null)ingestionSpec=DocumentTypes.IngestionSpec.defaults();}
    }
    public record Imported(DocumentTypes.View document,UUID jobId,boolean replayed) { }
    public record ScheduleRequest(@NotNull @Positive Long version,@NotNull Boolean enabled,@NotBlank @Size(max=128) String cron,
            @NotBlank @Size(max=64) String timezone) { }
    public record ScheduleView(UUID documentId,boolean enabled,String cron,String timezone,long version,
            OffsetDateTime nextRunAt,String lastStatus,String lastReason,UUID lastRunId,String lastContentHash,int consecutiveFailures) { }
    public record RefreshRequest(@NotBlank @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9._:-]{7,127}") String requestId,
            boolean confirmOverwriteEdited,@Positive Long expectedDocumentVersion) { }
    public record RunView(UUID id,UUID documentId,String requestId,String triggerType,String status,UUID jobId,
            int attempts,int maxAttempts,Integer httpStatus,String reason,String errorMessage,OffsetDateTime nextRunAt,
            OffsetDateTime startedAt,OffsetDateTime completedAt,Long durationMs) { }
    public record Submitted(RunView run,boolean replayed) { }
}
