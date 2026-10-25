package com.lzj.rag.backend.parser;
import com.lzj.rag.framework.convention.result.Result;
import com.lzj.rag.framework.starter.web.result.Results;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
public class FormatController {
    private final FormatProperties config;
    public FormatController(FormatProperties config){this.config=config;}
    @GetMapping("/api/knowledge/formats")
    public Result<List<Map<String,Object>>> formats() {
        return Results.success(List.of(
            row("txt,md,markdown","LOCAL","LOCAL_ALIAS","Strict UTF-8; fidelity aliases the same structural parser"),
            row("csv,xls,xlsx","LOCAL","LOCAL_ALIAS","RFC4180 or POI formulas; fidelity aliases the same table parser"),
            row("pdf","LOCAL_TEXT_ONLY",config.mineru().enabled()?"MINERU_CONFIGURED":"MINERU_DISABLED","Fast has no OCR; fidelity requires exact MinerU 2.7.6. Configured does not mean externally verified"),
            row("doc,docx,ppt,pptx","LOCAL","UNAVAILABLE","Tika basic text/tables and available locations; no Office fidelity route"),
            row("png,jpg,jpeg",config.vlm().enabled()?"VLM_CONFIGURED":"VLM_DISABLED",config.vlm().enabled()?"VLM_ALIAS_CONFIGURED":"VLM_DISABLED","Both profiles use AI vision; configured does not mean externally verified")));
    }
    private Map<String,Object> row(String extensions,String fast,String fidelity,String notes){return Map.of("extensions",List.of(extensions.split(",")),"fast",fast,"fidelity",fidelity,"notes",notes);}
}
