package com.lzj.rag.backend.refresh.service.bo;

import com.lzj.rag.backend.document.service.bo.IngestionSpecBO;

/**
 * 远程文档导入参数，包含源地址、文件名及可选入库配置。
 *
 * @param url 经过协议、主机和端口许可校验的远程地址。
 * @param filename 经过业务校验的文件名。
 * @param name 当前业务资源或策略的公开名称。
 * @param ingestionSpec 文档固定的解析及切片配置。
 * @param ingest 导入成功后是否立即提交入库任务。
 */
public record RemoteImportBO(
        String url, String filename, String name, IngestionSpecBO ingestionSpec, boolean ingest) {

    /** 校验并补齐缺省配置，构造可直接使用的配置快照。 */
    public RemoteImportBO {
        if (ingestionSpec == null) ingestionSpec = IngestionSpecBO.defaults();
    }
}
