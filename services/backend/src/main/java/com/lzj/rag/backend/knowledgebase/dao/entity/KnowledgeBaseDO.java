package com.lzj.rag.backend.knowledgebase.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lzj.rag.backend.knowledgebase.dao.handler.JsonbTypeHandler;

import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 知识库持久化实体，保存所有权、模型绑定和索引版本。 */
@Getter
@Setter
@TableName(value = "kb_knowledge_base", autoResultMap = true)
public class KnowledgeBaseDO {

    /** 当前业务对象的唯一标识。 */
    @TableId(type = IdType.INPUT)
    private UUID id;

    /** 数据所属租户标识。 */
    private String tenantId;

    /** 数据所有者的业务用户标识。 */
    private String ownerId;

    /** 当前业务资源或策略的公开名称。 */
    private String name;

    /** 资源描述或视觉模型生成的图片描述。 */
    private String description;

    /** 后端公开的嵌入配置标识。 */
    private String embeddingProfileId;

    /** 固定嵌入模型的逻辑标识。 */
    private String embeddingModelId;

    /** 固定嵌入模型的供应商标识。 */
    private String embeddingProvider;

    /** 固定向量维度。 */
    private Integer embeddingDimension;

    /** 后端嵌入配置的稳定修订摘要。 */
    private String embeddingConfigRevision;

    /** 已固定的 AI 服务配置修订摘要。 */
    private String embeddingAiConfigRevision;

    /** 提交时固定的嵌入配置 JSON 快照。 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String embeddingConfigJson;

    /** 知识库固定绑定的向量命名空间。 */
    private String vectorNamespace;

    /** 创建该记录的业务用户标识。 */
    private String createdBy;

    /** 最近修改记录的业务用户标识。 */
    private String updatedBy;

    /** 当前记录版本或本次请求固定的版本标记。 */
    private Long version;

    /** 记录创建时间。 */
    private OffsetDateTime createdAt;

    /** 记录最近更新时间。 */
    private OffsetDateTime updatedAt;

    /** 软删除时间；未删除时为空。 */
    private OffsetDateTime deletedAt;
}
