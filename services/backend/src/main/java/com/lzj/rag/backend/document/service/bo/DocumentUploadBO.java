package com.lzj.rag.backend.document.service.bo;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

/**
 * 上传时提供的业务名称、描述及可选处理配置。
 *
 * @param name 当前业务资源或策略的公开名称。
 * @param ingestionSpec 文档固定的解析及切片配置。
 */
public record DocumentUploadBO(@Size(max = 255) String name, @Valid IngestionSpecBO ingestionSpec) {

    /** 校验并补齐缺省配置，构造可直接使用的配置快照。 */
    public DocumentUploadBO {
        if (ingestionSpec == null) ingestionSpec = IngestionSpecBO.defaults();
    }
}
