package com.lzj.rag.backend.knowledgebase;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.lzj.rag.backend.persistence.JsonbTypeHandler;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@TableName(value = "kb_knowledge_base", autoResultMap = true)
public class KnowledgeBase {
    @TableId(type = IdType.INPUT)
    private UUID id;
    private String tenantId;
    private String ownerId;
    private String name;
    private String description;
    private String embeddingProfileId;
    private String embeddingModelId;
    private String embeddingProvider;
    private Integer embeddingDimension;
    private String embeddingConfigRevision;
    private String embeddingAiConfigRevision;
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String embeddingConfigJson;
    private String vectorNamespace;
    private String createdBy;
    private String updatedBy;
    private Long version;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private OffsetDateTime deletedAt;
}
