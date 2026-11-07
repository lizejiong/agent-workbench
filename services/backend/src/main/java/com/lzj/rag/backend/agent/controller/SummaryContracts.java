package com.lzj.rag.backend.agent.controller;

import com.lzj.rag.backend.agent.controller.AgentContracts.Snapshot;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;

/** 摘要只有派生内容；水位、来源指纹和证据依赖由 Java 计算。 */
public final class SummaryContracts {
    private SummaryContracts() {}
    public record Item(@NotBlank @Size(max=1000) String text,
            @NotNull @Size(min=1,max=8) List<@NotNull UUID> sourceMessageIds) {}
    public record Content(@NotNull @Size(max=12) List<@Valid Item> goals,
            @NotNull @Size(max=12) List<@Valid Item> constraints,
            @NotNull @Size(max=12) List<@Valid Item> facts,
            @NotNull @Size(max=12) List<@Valid Item> openQuestions) {
        public List<Item> items(){var items=new ArrayList<Item>();items.addAll(goals);items.addAll(constraints);items.addAll(facts);items.addAll(openQuestions);return items;}
    }
    public record Write(@NotNull UUID runId,@Min(0) long expectedRevision,
            @Min(1) @Max(24) int retainedTurns,@NotBlank @Pattern(regexp="[a-f0-9]{64}") String sourceHash,
            @NotNull @Valid Content content,@NotBlank @Size(max=120) String model,
            @NotBlank @Size(max=120) String promptVersion,@NotBlank @Pattern(regexp="[a-f0-9]{64}") String promptHash,
            @NotBlank @Size(max=120) String policyVersion) {}
    public record Use(@NotNull UUID runId,@Min(1) long revision,@Min(1) @Max(24) int retainedTurns,@NotBlank @Size(max=120) String policyVersion) {}
    public record Source(UUID messageId,String hash) {}
    public record Summary(long revision,long throughSequence,String sourceHash,List<Source> sources,
            Content content,List<Snapshot> evidence,String model,String promptVersion,String promptHash,String policyVersion) {}
}
