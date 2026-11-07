package com.lzj.rag.backend.agent.controller;

import jakarta.validation.constraints.*;
import java.time.Instant;

/** 固定枚举，仅影响表达；不接受事实、权限、指令或模型自动写入。 */
public final class PreferenceContracts {
    private PreferenceContracts() {}
    public enum Language { AUTO, ZH_CN, EN }
    public enum Detail { BALANCED, CONCISE, DETAILED }
    public enum Format { AUTO, PARAGRAPHS, BULLETS }
    public record View(long revision,long epoch,Language language,Detail detail,Format format,Instant updatedAt) {
        public static View defaults(){return new View(0,0,Language.AUTO,Detail.BALANCED,Format.AUTO,null);}
    }
    public record Write(@NotNull @Min(0) Long expectedRevision,@NotNull Language language,@NotNull Detail detail,@NotNull Format format) {}
    public record Reset(@NotNull @Min(0) Long expectedRevision) {}
}
