package com.lzj.rag.backend.agent.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;

/** 业务消息与安全工具视图；不接受图状态、系统提示词或凭据。 */
public final class AgentContracts {
    private AgentContracts() {}
    public record Create(@NotNull UUID requestId, @Size(max=120) String title,
            @Size(max=64) List<@NotNull UUID> kbIds, @Size(min=1,max=500) List<@NotNull UUID> documentIds) {}
    public record Rename(@NotBlank @Size(max=120) String title) {}
    public record Delegate(@NotBlank @Pattern(regexp="[a-z0-9-]{1,80}") String serviceId) {}
    public record Begin(@NotNull UUID requestId,@NotNull UUID messageId,
            @NotBlank @Size(max=4000) String question,@NotNull Instant deadline) {}
    public record Snapshot(@NotBlank @Pattern(regexp="K[a-f0-9]{32}_E[1-9][0-9]{0,3}") String evidenceId,
            @NotNull UUID kbId,@NotNull UUID documentId,@NotNull UUID chunkId,@Min(1) long generation,
            @NotBlank @Size(max=16384) String content,@NotBlank @Pattern(regexp="[a-f0-9]{64}") String sourceContentHash,
            @NotBlank @Pattern(regexp="[a-f0-9]{64}") String snapshotHash,
            @Size(max=64) Map<String,Object> sourceMetadata,boolean truncated,boolean toolTruncated,
            @Size(max=64) Map<String,Object> versions) {}
    public record Tool(@NotBlank @Size(max=128) String toolCallId,
            @NotBlank @Pattern(regexp="search_knowledge") String name,
            @NotBlank @Pattern(regexp="RUNNING|READY|NO_EVIDENCE|NEEDS_CLARIFICATION|UNAVAILABLE") String status,
            UUID requestId,@Size(max=32) String traceId,boolean degraded,@Size(max=32) String strategy,
            boolean toolTruncated,@Pattern(regexp="[A-Z0-9_]{1,80}") String errorCode,boolean retryable) {}
    public record ToolArguments(@NotBlank @Size(max=1000) String query) {}
    /** 工具正文只通过 evidenceIds 引用已校验快照，不能注入任意返回正文或身份字段。 */
    public record Block(@NotBlank @Pattern(regexp="B[a-f0-9]{32}") String blockId,
            @NotBlank @Pattern(regexp="answer|tool|hint|error") String kind,
            @Size(max=65536) String text,
            @Size(max=128) String toolCallId,@Pattern(regexp="search_knowledge") String name,
            @NotBlank @Pattern(regexp="streaming|pending|running|done|failed|interrupted") String status,
            Instant startedAt,@Min(0) @Max(120000) Long durationMs,
            @Pattern(regexp="B[a-f0-9]{32}") String batchId,@Min(0) @Max(31) Integer callIndex,
            @Valid ToolArguments arguments,
            @Size(max=128) List<@Pattern(regexp="K[a-f0-9]{32}_E[1-9][0-9]{0,3}") String> evidenceIds,
            @Pattern(regexp="[A-Z0-9_]{1,80}") String errorCode) {}
    public record Checkpoint(@Min(1) long sequence,
            @NotBlank @Pattern(regexp="RUNNING|COMPLETED|CANCELLED|FAILED|TIMED_OUT") String status,
            @NotNull @Size(max=65536) String content,
            @NotNull @Size(max=128) List<@Valid Snapshot> evidence,
            @NotNull @Size(max=128) List<@Pattern(regexp="K[a-f0-9]{32}_E[1-9][0-9]{0,3}") String> citations,
            @NotNull @Size(max=32) List<@Valid Tool> tools,
            @Size(max=120) String model,@Size(max=120) String promptVersion,
            @Pattern(regexp="[a-f0-9]{64}") String promptHash,
            @Pattern(regexp="[A-Z0-9_]{1,80}") String errorCode,
            @Size(max=128) List<@Valid Block> blocks,@Min(0) Long eventSeq) {}
    public record Conversation(UUID id,String title,List<UUID> kbIds,List<UUID> documentIds,
            Instant createdAt,Instant activeAt,String status) {}
    public record Run(UUID runId,UUID conversationId,UUID requestId,UUID userMessageId,UUID assistantMessageId,
            String traceId,String status,long checkpointSequence,Instant deadline,String errorCode,boolean created,PreferenceContracts.View preferences) {}
}
