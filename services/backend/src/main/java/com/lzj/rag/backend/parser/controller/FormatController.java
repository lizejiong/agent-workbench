package com.lzj.rag.backend.parser.controller;

import com.lzj.rag.backend.parser.config.FormatProperties;
import com.lzj.rag.framework.convention.result.Result;
import com.lzj.rag.framework.starter.web.result.Results;

import org.springframework.web.bind.annotation.*;

import java.util.*;

/** 公开格式与解析档位的支持情况，供上传界面选择处理方式。 */
@RestController
public class FormatController {

    /** 本地解析、MinerU 和视觉模型的开关、容量与时间限制。 */
    private final FormatProperties config;

    /**
     * 初始化当前组件，保存业务依赖及本次使用的配置。
     *
     * @param config 本地解析、MinerU 和视觉模型的开关、容量与时间限制。
     */
    public FormatController(FormatProperties config) {
        this.config = config;
    }

    /**
     * 列出各文件格式的解析策略、处理档位与当前启用状态。
     *
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @GetMapping("/api/knowledge/formats")
    public Result<List<Map<String, Object>>> formats() {
        return Results.success(
                List.of(
                        row(
                                "txt,md,markdown",
                                "LOCAL",
                                "LOCAL_ALIAS",
                                "Strict UTF-8; fidelity aliases the same structural parser"),
                        row(
                                "csv,xls,xlsx",
                                "LOCAL",
                                "LOCAL_ALIAS",
                                "RFC4180 or POI formulas; fidelity aliases the same table parser"),
                        row(
                                "pdf",
                                "LOCAL_TEXT_ONLY",
                                config.mineru().enabled() ? "MINERU_CONFIGURED" : "MINERU_DISABLED",
                                "Fast has no OCR; fidelity requires exact MinerU 2.7.6. Configured does not mean externally verified"),
                        row(
                                "doc,docx,ppt,pptx",
                                "LOCAL",
                                "UNAVAILABLE",
                                "Tika basic text/tables and available locations; no Office fidelity route"),
                        row(
                                "png,jpg,jpeg",
                                config.vlm().enabled() ? "VLM_CONFIGURED" : "VLM_DISABLED",
                                config.vlm().enabled() ? "VLM_ALIAS_CONFIGURED" : "VLM_DISABLED",
                                "Both profiles use AI vision; configured does not mean externally verified")));
    }

    /**
     * 组合单种格式在能力列表中的公开描述。
     *
     * @param extensions 当前格式允许的文件扩展名说明。
     * @param fast 快速解析档位的公开描述。
     * @param fidelity 高保真解析档位的公开描述。
     * @param notes 格式支持能力的补充说明。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    private Map<String, Object> row(String extensions, String fast, String fidelity, String notes) {
        return Map.of(
                "extensions",
                List.of(extensions.split(",")),
                "fast",
                fast,
                "fidelity",
                fidelity,
                "notes",
                notes);
    }
}
