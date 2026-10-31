package com.lzj.rag.backend.chunk.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.lzj.rag.backend.infra.persistence.JsonbTypeHandler;

import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 切片持久化实体，包含所属代次、启用状态和人工编辑信息。 */
@Getter
@Setter
@TableName(value = "kb_chunk", autoResultMap = true)
public class ChunkDO {

    /** 当前业务对象的唯一标识。 */
    @TableId(type = IdType.INPUT)
    private UUID id;

    /** 知识库唯一标识。 */
    private UUID kbId;

    /** 文档唯一标识。 */
    private UUID documentId;

    /** 切片或任务所属的数据代次。 */
    private Long generation;

    /** 数据所属租户标识。 */
    private String tenantId;

    /** 数据所有者的业务用户标识。 */
    private String ownerId;

    /** 切片在所属文档代次内的顺序位置。 */
    private Integer chunkIndex;

    /** 用于展示或维护的正文内容。 */
    private String content;

    /** 发送给嵌入模型的文本，可能包含章节或表格语义补充。 */
    private String embeddingText;

    /** 源内容或切片正文的 SHA-256 摘要。 */
    private String contentHash;

    /** 按 Unicode 码点统计的正文字符数。 */
    private Integer charCount;

    /** 切片的 Token 计数或估算值。 */
    private Integer tokenCount;

    /** Token 数是否来自估算。 */
    private Boolean tokenCountEstimated;

    /** 是否启用该资源或能力。 */
    private Boolean enabled;

    /** 切片是否经过人工编辑。 */
    private Boolean edited;

    /** 持久化存储的切片来源元数据 JSON。 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String metadataJson;

    /** 固定嵌入模型的逻辑标识。 */
    private String embeddingModelId;

    /** 后端嵌入配置的稳定修订摘要。 */
    private String embeddingConfigRevision;

    /** 本次实际使用或任务固定的 AI 配置修订摘要。 */
    private String aiConfigRevision;

    /** 创建该记录的业务用户标识。 */
    private String createdBy;

    /** 最近修改记录的业务用户标识。 */
    private String updatedBy;

    /** 记录创建时间。 */
    private OffsetDateTime createdAt;

    /** 记录最近更新时间。 */
    private OffsetDateTime updatedAt;
}
