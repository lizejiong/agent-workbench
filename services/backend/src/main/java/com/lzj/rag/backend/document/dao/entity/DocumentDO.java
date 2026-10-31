package com.lzj.rag.backend.document.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.lzj.rag.backend.infra.persistence.JsonbTypeHandler;

import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 文档持久化实体，分别记录源文件、处理配置和当前可检索代次。 */
@Getter
@Setter
@TableName(value = "kb_document", autoResultMap = true)
public class DocumentDO {

    /** 当前业务对象的唯一标识。 */
    @TableId(type = IdType.INPUT)
    private UUID id;

    /** 知识库唯一标识。 */
    private UUID kbId;

    /** 数据所属租户标识。 */
    private String tenantId;

    /** 数据所有者的业务用户标识。 */
    private String ownerId;

    /** 当前业务资源或策略的公开名称。 */
    private String name;

    /** 源文件原始名称。 */
    private String originalFilename;

    /** 文档来源类型，如本地上传或 URL 导入。 */
    private String sourceType;

    /** 远程文档配置的原始源地址。 */
    private String sourceUrl;

    /** 对象存储中的源文件键。 */
    private String objectKey;

    /** 源文件 MIME 类型。 */
    private String mimeType;

    /** 源文件字节数。 */
    private Long fileSize;

    /** 源内容或切片正文的 SHA-256 摘要。 */
    private String contentHash;

    /** 是否启用该资源或能力。 */
    private Boolean enabled;

    /** 文档当前入库处理状态。 */
    private String processStatus;

    /** 文档固定的解析及切片配置。 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String ingestionSpec;

    /** 业务处理配置修订，用于识别任务输入是否已失效。 */
    private Long configRevision;

    /** 当前可被检索的已发布代次；尚未发布时为空。 */
    private Long activeGeneration;

    /** 切片数量。 */
    private Long chunkCount;

    /** 当前记录版本或本次请求固定的版本标记。 */
    private Long version;

    /** 文档人工维护修订计数。 */
    private Long manualRevision;

    /** 文档是否存在需要覆盖确认的人工维护变更。 */
    private Boolean hasManualChanges;

    /** 创建该记录的业务用户标识。 */
    private String createdBy;

    /** 最近修改记录的业务用户标识。 */
    private String updatedBy;

    /** 记录创建时间。 */
    private OffsetDateTime createdAt;

    /** 记录最近更新时间。 */
    private OffsetDateTime updatedAt;

    /** 文档最近一次成功发布入库结果的时间。 */
    private OffsetDateTime lastIngestedAt;

    /** 软删除时间；未删除时为空。 */
    private OffsetDateTime deletedAt;
}
