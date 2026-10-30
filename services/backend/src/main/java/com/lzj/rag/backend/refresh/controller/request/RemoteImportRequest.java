package com.lzj.rag.backend.refresh.controller.request;

import com.lzj.rag.backend.document.controller.request.IngestionSpecRequest;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

/**
 * 远程文档导入参数，包含源地址、文件名及可选入库配置。
 *
 * @param url 经过协议、主机和端口许可校验的远程地址。
 * @param filename 经过业务校验的文件名。
 * @param name 当前业务资源或策略的公开名称。
 * @param ingestionSpec 文档固定的解析及切片配置。
 * @param ingest 导入成功后是否立即提交入库任务。
 */
public record RemoteImportRequest(
        @NotBlank @Size(max = 2048) String url,
        @NotBlank @Size(max = 255) String filename,
        @Size(max = 255) String name,
        @Valid IngestionSpecRequest ingestionSpec,
        boolean ingest) {

    /** 校验并补齐缺省配置，构造可直接使用的配置快照。 */
    public RemoteImportRequest {
        if (ingestionSpec == null) ingestionSpec = IngestionSpecRequest.defaults();
    }
}
