package com.lzj.rag.backend.tools;

import jakarta.validation.constraints.*;
import java.util.*;

public final class ToolContracts {
    private ToolContracts(){}
    public record Server(@NotBlank @Size(max=120) String name,@NotBlank @Size(max=1000) String url,
        @Pattern(regexp="env:[A-Z][A-Z0-9_]{1,100}") String credentialRef,boolean enabled,
        @Min(1) Integer expectedVersion){}
    public record Publish(@NotBlank @Pattern(regexp="[a-f0-9]{64}") String schemaHash,
        boolean enabled,boolean readOnly,@Min(100) @Max(40000) int timeoutMs,
        @Min(512) @Max(16384) int maxResultBytes){}
    public record Grant(@NotBlank @Size(max=128) String userId,boolean enabled){}
    public record Start(@NotBlank @Pattern(regexp="T[a-f0-9]{32}") String callId,
        @NotBlank @Size(max=128) String providerCallId,@NotBlank @Size(max=200) String toolKey,
        @Min(1) int configVersion,@Pattern(regexp="[a-f0-9]{64}") String schemaHash,
        @Min(0) @Max(32) int batch,@Min(0) @Max(31) int order,@NotNull Map<String,Object> arguments){}
    public record Finish(@NotBlank @Pattern(regexp="T[a-f0-9]{32}") String callId,
        @NotBlank @Pattern(regexp="success|empty|invalid_arguments|denied|timeout|unavailable|failed|cancelled") String status,
        @NotNull Map<String,Object> modelContent,@NotNull @Size(max=1000) String summary,
        @NotNull @Size(max=128) List<@Size(max=80) String> evidenceRefs,
        @NotBlank @Pattern(regexp="knowledge|mcp|none") String sourceType,
        @Pattern(regexp="[A-Z0-9_]{1,80}") String errorCode,boolean truncated,
        @Min(0) @Max(120000) int durationMs){}
    public record Source(@NotBlank @Size(max=80) String evidenceId){}
    public record Validate(@NotNull @Size(max=128) List<@Pattern(regexp="M[a-f0-9]{32}") String> sourceIds){}
    public record History(@NotNull @Size(max=512) List<@NotNull UUID> messageIds){}
}
